# Leptos DeviceConnector

A minimal Rust-based Linux notification receiver with an Android companion app that forwards Android notifications over the local Wi-Fi network.

## Overview

- `src/main.rs`: pure Rust TCP server using only the standard library.
- `android-client/`: Android application using `NotificationListenerService` and native socket APIs.

## Linux Mint setup

### Prerequisites

- Rust toolchain installed (`rustup` + `cargo`).
- Optional: `notify-send` available via `libnotify-bin` for desktop notifications.
- Make sure the Linux host and Android device are on the same local network.
- If the system uses a firewall, allow the chosen port (default `14353`).

### Build and run

```sh
cd /home/satwik/Projects/rust/leptos-deviceconnector
cargo build --release
./target/release/leptos-deviceconnector --port 14353 --auth my-secret-token --notify
```

Alternative run form:

```sh
cargo run --release -- --port 14353 --auth my-secret-token --notify
```

### Notes

- `--port`, `-p`: port to listen on (default `14353`)
- `--bind`, `-b`: bind address (default `0.0.0.0`)
- `--auth`, `-a`: require this token from clients
- `--notify`, `-n`: call `notify-send` if installed

### Protocol

Client messages are newline-delimited UTF-8 strings.

Supported commands:

- `AUTH|<token>`
- `NOTIFY|<title>|<body>`
- `FILE|<filename>|<size>`
- `PING`

Escape rules for fields:

- `\\` for literal backslash
- `\|` for literal pipe
- `\n`, `\r`, `\t`

## Android setup

### Prerequisites

- Android Studio installed or a device that can install APKs.
- Android device on the same Wi-Fi network as the Linux host.
- Notification access permission granted to the app.

### Import and build

1. Open Android Studio.
2. Select "Open" and choose the `android-client/` folder.
3. Allow Android Studio to sync and configure the Gradle project.
4. Use a compatible JDK (Java 17) if Android Studio prompts.
5. Build and run the `app` module on the target device.

If the Gradle wrapper is missing, Android Studio will prompt to generate or configure it.

### Configure the Android app

1. Open the installed app on your device.
2. Enter the Linux Mint host address (for example `192.168.1.12`).
3. Enter the port used by the Rust server (default `14353`).
4. Enter the same auth token used by the Rust server, or leave blank if the server has no auth token.
5. Save settings.
6. Press "Open notification access settings" and grant notification listener permission to the app.

### Behavior

- When a notification is posted on Android, the service connects to the Linux server.
- If configured, it sends `AUTH|token` and waits for `OK`.
- Then it sends `NOTIFY|title|body`.
- The Linux server logs the notification and optionally triggers a desktop alert.

## Example setup

On Linux Mint:

```sh
cargo run --release -- --port 14353 --auth super-secret-token --notify --store-dir received_files
```

On Android:

- Host: `192.168.1.12`
- Port: `14353`
- Auth token: `super-secret-token`

## File transfer

The Android app now supports file transfer via the "Send file to Linux server" button.

1. Tap "Send file to Linux server".
2. Choose any file from the Android file picker.
3. The app sends `FILE|<filename>|<size>` and then the raw byte payload.
4. The Linux server saves files into the configured `--store-dir` directory.

The default receive directory is `received_files` in the server working directory.

## Security notes

- Keep the auth token secret and use it on the server and client.
- Only use this system on trusted local networks.
- If you need stronger security later, add encrypted transport such as TLS or SSH tunneling.

## Future work

- Add file transfer support over the same TCP protocol.
- Add message acknowledgments and retries.
- Add a Linux GUI or background service wrapper.
- Add encrypted transport support and message integrity checks.
