use std::env;
use std::fs::{self, File};
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::Path;
use std::process::Command;
use std::sync::Arc;
use std::thread;

struct Config {
    bind_address: String,
    auth_token: Option<String>,
    notify_desktop: bool,
    receive_dir: String,
}

enum ServerMessage {
    Auth(String),
    Notify { title: String, body: String },
    File { name: String, size: usize },
    Ping,
    Unknown(String),
}

fn main() -> io::Result<()> {
    let config = parse_args();
    fs::create_dir_all(&config.receive_dir)?;
    println!("Starting notification server on {}", config.bind_address);
    let listener = TcpListener::bind(&config.bind_address)?;
    let config = Arc::new(config);

    for connection in listener.incoming() {
        match connection {
            Ok(stream) => {
                let config = Arc::clone(&config);
                thread::spawn(move || {
                    if let Err(e) = handle_connection(stream, config) {
                        eprintln!("Connection error: {}", e);
                    }
                });
            }
            Err(err) => {
                eprintln!("Failed to accept connection: {}", err);
            }
        }
    }

    Ok(())
}

fn parse_args() -> Config {
    let mut bind_address = String::from("0.0.0.0:14353");
    let mut auth_token = None;
    let mut notify_desktop = false;
    let mut receive_dir = String::from("received_files");

    let mut args = env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--port" | "-p" => {
                if let Some(port) = args.next() {
                    bind_address = format!("0.0.0.0:{}", port);
                } else {
                    eprintln!("Missing value after {}", arg);
                    std::process::exit(1);
                }
            }
            "--bind" | "-b" => {
                if let Some(address) = args.next() {
                    bind_address = address;
                } else {
                    eprintln!("Missing value after {}", arg);
                    std::process::exit(1);
                }
            }
            "--auth" | "-a" => {
                if let Some(token) = args.next() {
                    auth_token = Some(token);
                } else {
                    eprintln!("Missing value after {}", arg);
                    std::process::exit(1);
                }
            }
            "--notify" | "-n" => {
                notify_desktop = true;
            }
            "--store-dir" | "--dir" => {
                if let Some(dir) = args.next() {
                    receive_dir = dir;
                } else {
                    eprintln!("Missing value after {}", arg);
                    std::process::exit(1);
                }
            }
            "--help" | "-h" => {
                print_usage();
                std::process::exit(0);
            }
            _ => {
                eprintln!("Unknown argument: {}", arg);
                print_usage();
                std::process::exit(1);
            }
        }
    }

    Config {
        bind_address,
        auth_token,
        notify_desktop,
        receive_dir,
    }
}

fn print_usage() {
    println!("Notification server for Linux using std Rust only.");
    println!("Usage: deviceconnector [options]");
    println!("Options:");
    println!("  -p, --port <port>        Listen port (default 14353)");
    println!("  -b, --bind <address>     Bind address (default 0.0.0.0)");
    println!("  -a, --auth <token>       Require auth token from clients");
    println!("  -n, --notify             Try to deliver desktop notifications with notify-send");
    println!(
        "  --store-dir <dir>        Directory to save received files (default received_files)"
    );
    println!("  -h, --help               Show this help message");
}

fn handle_connection(stream: TcpStream, config: Arc<Config>) -> io::Result<()> {
    let peer = stream
        .peer_addr()
        .map(|addr| addr.to_string())
        .unwrap_or_else(|_| "unknown".into());
    println!("Accepted connection from {}", peer);

    let mut reader = BufReader::new(stream.try_clone()?);
    let mut writer = stream;
    let mut auth_verified = config.auth_token.is_none();
    let mut line = String::new();

    while reader.read_line(&mut line)? > 0 {
        let raw_line = line.trim_end_matches(&['\r', '\n'][..]).to_string();
        line.clear();

        if raw_line.is_empty() {
            continue;
        }

        if !auth_verified {
            let message = parse_message(&raw_line);
            match message {
                ServerMessage::Auth(token) => {
                    if let Some(expected) = &config.auth_token {
                        if token == *expected {
                            auth_verified = true;
                            writer.write_all(b"OK\n")?;
                            writer.flush()?;
                            println!("{} authenticated successfully", peer);
                            continue;
                        }
                    }
                    writer.write_all(b"ERROR authentication failed\n")?;
                    writer.flush()?;
                    println!("{} failed authentication", peer);
                    return Ok(());
                }
                _ => {
                    writer.write_all(b"ERROR auth required\n")?;
                    writer.flush()?;
                    println!("{} sent message before auth", peer);
                    return Ok(());
                }
            }
        }

        let message = parse_message(&raw_line);
        match message {
            ServerMessage::Notify { title, body } => {
                handle_notification(&title, &body, &config);
                writer.write_all(b"OK\n")?;
                writer.flush()?;
            }
            ServerMessage::File { name, size } => {
                if let Err(err) = handle_file(&mut reader, &mut writer, &name, size, &config, &peer)
                {
                    let response = format!("ERROR file transfer failed: {}\n", err);
                    writer.write_all(response.as_bytes())?;
                    writer.flush()?;
                    eprintln!("Failed receiving file from {}: {}", peer, err);
                }
            }
            ServerMessage::Ping => {
                writer.write_all(b"PONG\n")?;
                writer.flush()?;
            }
            ServerMessage::Auth(_) => {
                writer.write_all(b"ERROR already authenticated\n")?;
                writer.flush()?;
            }
            ServerMessage::Unknown(text) => {
                let response = format!("ERROR unknown message type: {}\n", text);
                writer.write_all(response.as_bytes())?;
                writer.flush()?;
            }
        }
    }

    println!("Connection closed: {}", peer);
    Ok(())
}

fn handle_file(
    reader: &mut BufReader<TcpStream>,
    writer: &mut TcpStream,
    file_name: &str,
    file_size: usize,
    config: &Config,
    peer: &str,
) -> io::Result<()> {
    let safe_name = sanitize_filename(file_name);
    let target_path = Path::new(&config.receive_dir).join(safe_name);
    let mut file = File::create(&target_path)?;
    let mut remaining = file_size;
    let mut buffer = [0u8; 8192];

    while remaining > 0 {
        let read_size = std::cmp::min(remaining, buffer.len());
        reader.read_exact(&mut buffer[..read_size])?;
        file.write_all(&buffer[..read_size])?;
        remaining -= read_size;
    }

    writer.write_all(b"OK\n")?;
    writer.flush()?;
    println!(
        "Received file from {}: {} bytes saved to {}",
        peer,
        file_size,
        target_path.display()
    );
    Ok(())
}

fn sanitize_filename(name: &str) -> String {
    let last_component = name
        .rsplit(|c| c == '/' || c == '\\')
        .next()
        .unwrap_or(name);

    last_component
        .chars()
        .map(|ch| {
            if ch == '/' || ch == '\\' || ch == '\0' {
                '_'
            } else {
                ch
            }
        })
        .collect()
}

fn parse_message(line: &str) -> ServerMessage {
    let fields = split_escaped(line, '|');
    let command = fields.get(0).map(|s| s.as_str()).unwrap_or("");

    match command {
        "AUTH" if fields.len() == 2 => {
            let token = decode_field(&fields[1]);
            ServerMessage::Auth(token)
        }
        "NOTIFY" if fields.len() == 3 => {
            let title = decode_field(&fields[1]);
            let body = decode_field(&fields[2]);
            ServerMessage::Notify { title, body }
        }
        "FILE" if fields.len() == 3 => {
            let name = decode_field(&fields[1]);
            if let Ok(size) = fields[2].parse::<usize>() {
                ServerMessage::File { name, size }
            } else {
                ServerMessage::Unknown(command.to_string())
            }
        }
        "PING" => ServerMessage::Ping,
        _ => ServerMessage::Unknown(command.to_string()),
    }
}

fn split_escaped(input: &str, delimiter: char) -> Vec<String> {
    let mut parts = Vec::new();
    let mut current = String::new();
    let mut chars = input.chars();

    while let Some(ch) = chars.next() {
        if ch == '\\' {
            if let Some(next) = chars.next() {
                current.push(next);
            } else {
                current.push('\\');
            }
            continue;
        }

        if ch == delimiter {
            parts.push(current);
            current = String::new();
            continue;
        }

        current.push(ch);
    }

    parts.push(current);
    parts
}

fn decode_field(value: &str) -> String {
    let mut decoded = String::with_capacity(value.len());
    let mut chars = value.chars();

    while let Some(ch) = chars.next() {
        if ch == '\\' {
            match chars.next() {
                Some('n') => decoded.push('\n'),
                Some('r') => decoded.push('\r'),
                Some('t') => decoded.push('\t'),
                Some('\\') => decoded.push('\\'),
                Some('|') => decoded.push('|'),
                Some(other) => {
                    decoded.push('\\');
                    decoded.push(other);
                }
                None => decoded.push('\\'),
            }
        } else {
            decoded.push(ch);
        }
    }

    decoded
}

fn handle_notification(title: &str, body: &str, config: &Config) {
    println!("[Notification] {}: {}", title, body);

    if config.notify_desktop {
        match notify_desktop(title, body) {
            Ok(true) => println!("Desktop notification delivered."),
            Ok(false) => println!("notify-send not available, skipped desktop notification."),
            Err(err) => eprintln!("Failed to execute notify-send: {}", err),
        }
    }
}

fn notify_desktop(title: &str, body: &str) -> io::Result<bool> {
    if fs::metadata("/usr/bin/notify-send").is_err() && fs::metadata("/bin/notify-send").is_err() {
        return Ok(false);
    }

    let status = Command::new("notify-send").arg(title).arg(body).status()?;

    Ok(status.success())
}
