# OpenDroidMic

**Turn your Android phone into a low-latency microphone for Linux.**

Android → Wi-Fi/UDP → Opus → PipeWire → Any Linux application.

```
Android Phone ──── Wi-Fi ────► Linux PC ──── PipeWire ────► Discord/OBS/Browser
  AudioRecord                    UDP receiver                 "OpenDroidMic"
  Opus encode                    Opus decode
  48kHz mono                     Jitter buffer
```

## Quick Start

### Option A: Download pre-built binary (recommended)

Go to [Releases](../../releases) and download the latest version:

- **Linux x86_64**: `opendroidmic-*-linux-amd64.tar.gz`
- **Android**: `opendroidmic-*-android.apk`

```bash
# Linux
tar xzf opendroidmic-*-linux-amd64.tar.gz
cd opendroidmic-*
./opendroidmic
```

```bash
# Android — transfer APK to phone and install, or:
adb install opendroidmic-*-android.apk
```

The binaries only need PipeWire and libopus at runtime (which PipeWire-based distros already have).

### Option B: Build from source

#### 1. Install system dependencies

**Arch/CachyOS:**
```bash
sudo pacman -S rust pipewire opus pkg-config
```

**Debian/Ubuntu:**
```bash
sudo apt install rustc cargo libpipewire-0.3-dev libopus-dev pkg-config build-essential
```

**Fedora:**
```bash
sudo dnf install rust pipewire-devel opus-devel pkg-config gcc
```

#### 2. Build the Linux receiver

```bash
git clone https://github.com/dereferencex/opendroidmic.git
cd opendroidmic/linux
cargo build --release
```

Binary at: `target/release/opendroidmic`

#### 3. Start the receiver

```bash
./target/release/opendroidmic
```

You should see:
```
OpenDroidMic v0.1.0
PipeWire virtual source started: 48000 Hz, 1 ch, 960 samples/frame
Listening for Android connections on port 38471...
```

### 4. Install the Android app

```bash
# If you have ADB:
cd opendroidmic/android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk

# Or open android/ in Android Studio and click Run
```

### 5. Connect

1. Open OpenDroidMic on your phone
2. Enter your **Linux PC's IP address** and port `38471`
3. Tap **Start Streaming**
4. Grant microphone permission
5. Select **"OpenDroidMic"** as mic in Discord/OBS/Browser/etc.

Find your PC's IP: `ip addr show | grep "inet "`

## Features

- **Low latency** — ~20ms base path on local Wi-Fi; RNNoise adds about 10ms
- **Adjustable Opus codec** — 16–128 kbps, Auto/Wideband/Fullband, 48 kHz mono
- **Pickup profiles** — Desktop, Call, and Native capture tuning
- **RNNoise suppression** — optional on-device neural background-noise removal
- **Direct RTP/Opus mode** — stream straight into PipeWire without the Rust receiver
- **PipeWire virtual mic** — appears as a real microphone in all Linux apps
- **Adaptive jitter buffer** — auto-tunes 1-5 frames based on network conditions
- **Auto-reconnect** — exponential backoff (500ms → 8s), up to 10 attempts
- **Keepalive** — PING/PONG every 5s, detects dead connections in 15s
- **mDNS discovery** — auto-finds receiver on local network (Avahi/Zeroconf)
- **QR code pairing** — run `./opendroidmic --qr`, scan from phone
- **Background streaming** — foreground service, notification shows connection status
- **Last-used memory** — remembers host/port between sessions

## Direct RTP/Opus to PipeWire

PipeWire 1.6.9 or newer can receive the Android stream directly. Install the
included source configuration:

```bash
mkdir -p ~/.config/pipewire/pipewire.conf.d
cp linux/pipewire/opendroidmic-rtp.conf \
  ~/.config/pipewire/pipewire.conf.d/90-opendroidmic-rtp.conf
systemctl --user restart pipewire pipewire-pulse
```

Then select **RTP / Opus (PipeWire)** in the Android app, enter the Linux PC's
address, keep the default port `38472`, and start streaming. The source appears
as **OpenDroidMic RTP**. RTP mode uses payload type 127, a 48 kHz clock, and one
20 ms Opus frame per packet.

The app keeps transport, host, and port fixed while streaming. Opus bitrate and
bandwidth remain editable and take effect on the next complete 20 ms frame.

RTP mode is unauthenticated and unencrypted; use it only on a trusted LAN.

## CLI Options

```bash
./opendroidmic                          # Default: port 38471
./opendroidmic --port 5000              # Custom port
./opendroidmic --verbose                # Detailed logging
./opendroidmic --qr                     # Print QR code for pairing
./opendroidmic --port 5000 --qr --verbose  # Combine options
```

## Connection Methods

**Manual** — Type IP:port in the app.

**Auto-discover** — Requires Avahi on Linux:
```bash
# Install Avahi
sudo pacman -S avahi nss-mdns        # Arch
sudo apt install avahi-daemon libnss-mdns  # Debian

# Enable
sudo systemctl enable --now avahi-daemon

# Install OpenDroidMic service
sudo cp linux/avahi/opendroidmic.service /etc/avahi/services/
sudo systemctl restart avahi-daemon
```

**QR code** — Run `./opendroidmic --qr`, scan from the app.

## Requirements

| Component | Pre-built binary (download) | Build from source |
|-----------|---------------------------|-------------------|
| Linux runtime | PipeWire 1.0+, libopus | Same |
| Linux build | None | Rust 1.75+, libpipewire-dev, libopus-dev |
| Android | Android 8.0+ (API 26) | + Android SDK 35, NDK 27.0.12077973, CMake 3.22.1, JDK 17 |

## Test Without Phone

```bash
# Terminal 1: Start receiver
./target/release/opendroidmic

# Terminal 2: Send synthetic 440Hz tone
./target/release/opendroidmic-test-sender 127.0.0.1:38471
```

## Troubleshooting

**"OpenDroidMic" doesn't appear in mic list:**
```bash
wpctl status                    # Check PipeWire nodes
pw-cli ls Node | grep -i mic    # Search for virtual mic
```

**Android stuck on "Waiting for server...":**
- Verify same network: both on same Wi-Fi
- Check receiver is running: `ss -ulnp | grep 38471`
- Open UDP port: `sudo iptables -I INPUT -p udp --dport 38471 -j ACCEPT`
- Try loopback first: run receiver + test sender on same machine

**No audio / choppy audio:**
- Use 5GHz Wi-Fi instead of 2.4GHz
- Move closer to router
- Check receiver stats for packet loss/jitter
- Close other bandwidth-heavy apps

**Permission denied on `./gradlew`:**
```bash
chmod +x android/gradlew
```

**PipeWire not found (build error):**
```bash
# Arch
sudo pacman -S pipewire pipewire-pulse

# Debian/Ubuntu
sudo apt install libpipewire-0.3-dev pipewire

# Fedora
sudo dnf install pipewire-devel pipewire-pulse
```

## Project Structure

```
opendroidmic/
├── android/                        # Android app (Kotlin)
│   ├── app/src/main/java/com/opendroidmic/
│   │   ├── MainActivity.kt         # UI + discovery + QR
│   │   ├── AudioStreamService.kt   # Foreground service + reconnect
│   │   ├── OpusEncoderWrapper.kt   # Concentus Opus encoder
│   │   ├── StreamConfig.kt          # Transport and dynamic Opus settings
│   │   ├── AudioTransport.kt        # Transport interface
│   │   ├── OdmcTransport.kt         # Legacy ODMC handshake + keepalive
│   │   ├── RtpOpusTransport.kt      # Direct RTP/Opus UDP sender
│   │   ├── RtpOpusPacketizer.kt     # RFC 3550/RFC 7587 packetizer
│   │   ├── PcmFrameAccumulator.kt   # Exact 20 ms PCM framing
│   │   ├── Protocol.kt             # Binary packet format
│   │   ├── DiscoveryManager.kt     # mDNS/NSD discovery
│   │   └── QrScanActivity.kt       # CameraX + ML Kit QR scanner
│   └── app/build.gradle.kts
│
├── linux/                          # Linux receiver (Rust)
│   ├── src/
│   │   ├── main.rs                 # Entry point + UDP receiver
│   │   ├── pipewire.rs             # PipeWire virtual source
│   │   ├── protocol.rs             # Binary protocol (7 packet types)
│   │   ├── opus_dec.rs             # Opus decoder
│   │   ├── audio_buffer.rs         # Shared PCM ring buffer
│   │   ├── jitter.rs               # Adaptive jitter buffer
│   │   └── test_sender.rs          # Test audio sender
│   ├── avahi/                      # mDNS service files
│   ├── pipewire/                   # Direct RTP/Opus source config
│   └── Cargo.toml
│
├── protocol/PROTOCOL.md            # Full protocol specification
├── README.md
└── CONTRIBUTING.md
```

## Protocol

Quick summary — full spec in [protocol/PROTOCOL.md](protocol/PROTOCOL.md):

| Type | ID | Direction | Description |
|------|----|-----------|-------------|
| HELLO | 0 | Client→Server | Session token (8 bytes) |
| HELLO_ACK | 1 | Server→Client | Accept connection |
| AUDIO | 2 | Client→Server | Opus frame + seq + timestamp |
| PING | 3 | Client→Server | Keepalive request |
| PONG | 4 | Server→Client | Keepalive response |
| STOP | 5 | Both | Graceful disconnect |
| ERROR | 6 | Server→Client | Error with message |

20-byte header: magic `ODMC` + version + type + flags + seq + timestamp + payload_len + checksum.

## License

[MIT License](LICENSE)
