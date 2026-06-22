use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use aes_gcm::aead::{Aead, rand_core::RngCore};
use base64::{engine::general_purpose, Engine as _};
use rand::rngs::OsRng as RngOs;
use sha2::{Digest, Sha256};
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
    secure_mode: bool,
    daemon: bool,
    log_file: Option<String>,
}

enum ServerMessage {
    Auth(String),
    Notify { title: String, body: String },
    File { name: String, size: usize, data: Option<Vec<u8>> },
    Ping,
    Unknown(String),
}

fn main() -> io::Result<()> {
    let config = parse_args();
    if config.daemon && env::var_os("DEVICECONNECTOR_DAEMON").is_none() {
        launch_daemon(&config)?;
        return Ok(());
    }

    if config.secure_mode && config.auth_token.is_none() {
        eprintln!("Secure mode requires an auth token.");
        std::process::exit(1);
    }

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

fn launch_daemon(config: &Config) -> io::Result<()> {
    let exe = env::current_exe()?;
    let args: Vec<String> = env::args()
        .skip(1)
        .filter(|arg| arg != "--daemon")
        .collect();

    let mut command = std::process::Command::new(exe);
    command.args(&args);
    command.env("DEVICECONNECTOR_DAEMON", "1");

    if let Some(log_path) = &config.log_file {
        let log_file = File::create(log_path)?;
        let log_clone = log_file.try_clone()?;
        command.stdout(log_file);
        command.stderr(log_clone);
    } else {
        let null_file = File::create("/dev/null")?;
        let null_clone = null_file.try_clone()?;
        command.stdout(null_file);
        command.stderr(null_clone);
    }

    command.spawn()?;
    println!("Launched daemon process.");
    Ok(())
}

fn parse_args() -> Config {
    let mut bind_address = String::from("0.0.0.0:14353");
    let mut auth_token = None;
    let mut notify_desktop = false;
    let mut receive_dir = String::from("received_files");
    let mut secure_mode = false;
    let mut daemon = false;
    let mut log_file = None;

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
            "--secure" | "-s" => {
                secure_mode = true;
            }
            "--daemon" => {
                daemon = true;
            }
            "--log-file" => {
                if let Some(path) = args.next() {
                    log_file = Some(path);
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
        secure_mode,
        daemon,
        log_file,
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
    println!("  --store-dir <dir>        Directory to save received files (default received_files)");
    println!("  --secure, -s             Require encrypted transport and integrity checks");
    println!("  --daemon                 Spawn a background process and exit the parent");
    println!("  --log-file <path>        Log file for daemon mode");
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

        let message = parse_message(&raw_line, &config);
        if matches!(message, ServerMessage::Unknown(_)) {
            send_response(&mut writer, "ERROR invalid or encrypted message", &config)?;
            continue;
        }

        if !auth_verified {
            match message {
                ServerMessage::Auth(token) => {
                    if let Some(expected) = &config.auth_token {
                        if token == *expected {
                            auth_verified = true;
                            send_response(&mut writer, "OK", &config)?;
                            println!("{} authenticated successfully", peer);
                            continue;
                        }
                    }
                    send_response(&mut writer, "ERROR authentication failed", &config)?;
                    println!("{} failed authentication", peer);
                    return Ok(());
                }
                _ => {
                    send_response(&mut writer, "ERROR auth required", &config)?;
                    println!("{} sent message before auth", peer);
                    return Ok(());
                }
            }
        }

        match message {
            ServerMessage::Notify { title, body } => {
                handle_notification(&title, &body, &config);
                send_response(&mut writer, "OK", &config)?;
            }
            ServerMessage::File { name, size, data } => {
                if let Err(err) = if let Some(data) = data {
                    handle_file_bytes(&name, size, &data, &config, &peer)
                } else {
                    handle_file_raw(&mut reader, &mut writer, &name, size, &config, &peer)
                } {
                    let response = format!("ERROR file transfer failed: {}", err);
                    send_response(&mut writer, &response, &config)?;
                    eprintln!("Failed receiving file from {}: {}", peer, err);
                }
            }
            ServerMessage::Ping => {
                send_response(&mut writer, "PONG", &config)?;
            }
            ServerMessage::Auth(_) => {
                send_response(&mut writer, "ERROR already authenticated", &config)?;
            }
            ServerMessage::Unknown(text) => {
                let response = format!("ERROR unknown message type: {}", text);
                send_response(&mut writer, &response, &config)?;
            }
        }
    }

    println!("Connection closed: {}", peer);
    Ok(())
}

fn handle_file_bytes(
    file_name: &str,
    file_size: usize,
    data: &[u8],
    config: &Config,
    peer: &str,
) -> io::Result<()> {
    if data.len() != file_size {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "File size does not match payload size",
        ));
    }

    let safe_name = sanitize_filename(file_name);
    let target_path = Path::new(&config.receive_dir).join(safe_name);
    let mut file = File::create(&target_path)?;
    file.write_all(data)?;

    println!(
        "Received inline file from {}: {} bytes saved to {}",
        peer,
        file_size,
        target_path.display()
    );
    Ok(())
}

fn handle_file_raw(
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

    send_response(writer, "OK", &config)?;
    println!(
        "Received raw file from {}: {} bytes saved to {}",
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
        .map(|ch| if ch == '/' || ch == '\\' || ch == '\0' { '_' } else { ch })
        .collect()
}

fn parse_message(line: &str, config: &Config) -> ServerMessage {
    let fields = split_escaped(line, '|');
    let command = fields.get(0).map(|s| s.as_str()).unwrap_or("");

    if command == "ENCRYPTED" && fields.len() == 3 {
        if let Some(secret) = &config.auth_token {
            if let Ok(decrypted) = decrypt_payload(secret, &fields[1], &fields[2]) {
                return parse_message(&decrypted, config);
            }
        }
        return ServerMessage::Unknown("DECRYPT_FAIL".into());
    }

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
                ServerMessage::File { name, size, data: None }
            } else {
                ServerMessage::Unknown(command.to_string())
            }
        }
        "FILE" if fields.len() == 4 => {
            let name = decode_field(&fields[1]);
            if let Ok(size) = fields[2].parse::<usize>() {
                if let Ok(data) = general_purpose::STANDARD.decode(&fields[3]) {
                    ServerMessage::File { name, size, data: Some(data) }
                } else {
                    ServerMessage::Unknown(command.to_string())
                }
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

fn send_response(writer: &mut TcpStream, response: &str, config: &Config) -> io::Result<()> {
    let data = if config.secure_mode {
        let secret = config.auth_token.as_ref().expect("secure mode requires auth token");
        encrypt_response(secret, response)?
    } else {
        format!("{}\n", response)
    };
    writer.write_all(data.as_bytes())?;
    writer.flush()?;
    Ok(())
}

fn encrypt_response(secret: &str, response: &str) -> io::Result<String> {
    let key = Aes256Gcm::new_from_slice(&derive_key(secret)).map_err(|_| {
        io::Error::new(io::ErrorKind::Other, "Failed to initialize encryption key")
    })?;
    let mut nonce_bytes = [0u8; 12];
    RngOs.fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);
    let ciphertext = key
        .encrypt(nonce, response.as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::Other, "Encryption failed"))?;
    let nonce_b64 = general_purpose::STANDARD.encode(&nonce_bytes);
    let cipher_b64 = general_purpose::STANDARD.encode(&ciphertext);
    Ok(format!("ENCRYPTED|{}|{}\n", nonce_b64, cipher_b64))
}

fn decrypt_payload(secret: &str, nonce_b64: &str, ciphertext_b64: &str) -> Result<String, io::Error> {
    let key = Aes256Gcm::new_from_slice(&derive_key(secret))
        .map_err(|_| io::Error::new(io::ErrorKind::Other, "Failed to initialize decryption key"))?;
    let nonce_bytes = general_purpose::STANDARD
        .decode(nonce_b64)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "Invalid nonce"))?;
    let ciphertext = general_purpose::STANDARD
        .decode(ciphertext_b64)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "Invalid ciphertext"))?;
    let nonce = Nonce::from_slice(&nonce_bytes);
    let plaintext = key
        .decrypt(nonce, ciphertext.as_ref())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "Decryption failed"))?;
    String::from_utf8(plaintext).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "Invalid UTF-8"))
}

fn derive_key(auth_token: &str) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(auth_token.as_bytes());
    let result = hasher.finalize();
    let mut key = [0u8; 32];
    key.copy_from_slice(&result);
    key
}
