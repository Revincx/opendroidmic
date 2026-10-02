# OpenDroidMic Protocol

## Overview

OpenDroidMic supports two independent UDP transports:

1. **ODMC/1** — the compact protocol documented below, with handshake,
   keepalive, reconnection, and the custom Linux receiver.
2. **RTP/Opus** — RTP v2 with an RFC 7587 Opus payload, intended for direct
   reception by PipeWire's `libpipewire-module-rtp-source`.

RTP packets are not wrapped in an ODMC header and are not an ODMC packet type.

## ODMC/1 Transport

## Packet Structure

Every packet begins with a fixed 20-byte header followed by a variable-length payload.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        Magic (ODMC)                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Version (1)  |  Packet Type  |    Flags     |   Reserved     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Sequence Number                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       Timestamp (ms)                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           Payload Length        |         Checksum             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                      Payload (variable)                       |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Header Fields

| Field            | Size    | Description                           |
|------------------|---------|---------------------------------------|
| Magic            | 4 bytes | Always `ODMC` (0x4F444D43)           |
| Version          | 1 byte  | Protocol version (currently 1)        |
| Packet Type      | 1 byte  | See packet types below                |
| Flags            | 1 byte  | Reserved for future use               |
| Reserved         | 1 byte  | Must be zero                          |
| Sequence Number  | 4 bytes | Monotonic counter (LE u32)            |
| Timestamp        | 4 bytes | Milliseconds since stream start (LE)  |
| Payload Length   | 2 bytes | Length of payload in bytes (LE u16)   |
| Checksum         | 2 bytes | Reserved (0 for now)                  |

**Total header size: 20 bytes**

## Packet Types

| Value | Name       | Direction       | Description                     |
|-------|------------|-----------------|---------------------------------|
| 0     | HELLO      | Android → Linux | Initial handshake               |
| 1     | HELLO_ACK  | Linux → Android | Handshake response              |
| 2     | AUDIO      | Android → Linux | Opus-encoded audio frame         |
| 3     | PING       | Either          | Keepalive / latency measurement  |
| 4     | PONG       | Either          | Keepalive / latency response     |
| 5     | STOP       | Either          | Stream end                       |
| 6     | ERROR      | Either          | Error message                    |

## Packet Formats

### HELLO (Type 0)

Sent by Android to initiate a connection.

```
Payload:
  [0..8)   Session Token (u64, LE)
```

### HELLO_ACK (Type 1)

Sent by Linux in response to HELLO.

```
Payload:
  [0..8)   Session Token (u64, LE) - echoed from HELLO
  [8..12)  Sample Rate (u32, LE)   - e.g. 48000
  [12..16) Channels (u32, LE)      - e.g. 1
```

### AUDIO (Type 2)

Opus-encoded audio data.

```
Header:
  sequence  - Monotonic frame counter
  timestamp - Frame timestamp in milliseconds

Payload:
  Opus encoded frame (variable length, typically 60-200 bytes)
```

### PING (Type 3) / PONG (Type 4)

Used for keepalive and latency measurement. Either side may send PING;
the other responds with PONG echoing the same sequence and timestamp.

```
Payload:
  [0..4)   Sequence (u32, LE) - echoed in PONG
  [4..8)   Timestamp (u32, LE) - echoed in PONG
```

**Timing:**
- Android sends PING every 5 seconds while streaming.
- Linux sends PING every 5 seconds to detect client timeout.
- If no packets are received for 15 seconds, the connection is dropped.

### STOP (Type 5)

Sent by either side to signal end of stream.

```
Payload:
  [0..8)   Session Token (u64, LE)
```

## Audio Parameters

| Parameter      | Value              |
|----------------|--------------------|
| Sample Rate    | 48000 Hz           |
| Channels       | 1 (mono)           |
| Codec          | Opus               |
| Frame Duration | 20 ms (960 samples)|
| Bitrate        | 16–128 kbps, dynamically selectable |
| Opus Bandwidth | Auto, Wideband, or Fullband |
| Transport      | UDP                |
| Max Packet     | 1500 bytes         |

## Connection Lifecycle

```
Android                              Linux
   |                                   |
   |---- HELLO (session_token) ------->|
   |                                   |
   |<---- HELLO_ACK (token,rate) ------|
   |                                   |
   |<========== AUDIO STREAM =========>|
   |                                   |
   |  (PING every 5s from each side)   |
   |                                   |
   |---- STOP (session_token) -------->|
   |   or timeout after 15s silence    |
```

## Reliability Features

### Jitter Buffer (Linux)

The receiver uses an adaptive jitter buffer to smooth out network timing:

- **Low jitter** (<10ms): 1 frame buffer (~20ms latency)
- **Medium jitter** (10-30ms): 2 frame buffer (~40ms latency)
- **High jitter** (>30ms): 5 frame buffer (~100ms latency)

### Packet Loss Detection (Linux)

The receiver tracks:
- **Packets received** - total audio frames received
- **Packets lost** - detected via sequence number gaps
- **Packets reordered** - out-of-order delivery
- **Current jitter** - inter-arrival time variance

### Reconnection (Android)

The Android app implements automatic reconnection:
- Exponential backoff: 500ms, 1s, 2s, 4s, 8s (max)
- Up to 10 reconnect attempts
- HelloAck timeout: 3 seconds
- Pong timeout: 5 seconds

### Connection Timeout (Linux)

- Client is disconnected after 15 seconds of no packets
- Server sends periodic PINGs to detect client presence

## Discovery

### mDNS (Zeroconf)

The Linux receiver can advertise itself via mDNS using Avahi:

```
Service type: _opendroidmic._udp
Service name: OpenDroidMic
Port: 38471 (configurable)
TXT records: version=1, protocol=odmc
```

**Setup (one-time):**
```bash
sudo cp linux/avahi/opendroidmic.service /etc/avahi/services/
sudo systemctl restart avahi-daemon
```

**Dynamic publishing:**
```bash
./linux/avahi/publish-service.sh 38471
```

### QR Code Pairing

The Linux receiver can display a QR code encoding the connection URL:

```bash
./opendroidmic --qr --port 38471
```

This generates a QR code with the URL `odmc://<ip>:<port>`, which the
Android app can scan to auto-fill the address.

## Security Notes

- Session tokens are randomly generated (64-bit).
- The receiver validates packet sizes and rejects malformed packets.
- The receiver only accepts packets from the connected client address.
- The receiver should not be exposed to the internet.
- Consider adding encryption (DTLS/Noise) for future versions.

## RTP/Opus Transport

RTP/Opus mode sends one complete 20 ms Opus packet in each RTP packet:

```
[12-byte RTP v2 header][Opus packet]
```

There is no ODMC `HELLO`, `PING`, `PONG`, `STOP`, or reconnect state in this
mode. UDP does not provide receiver acknowledgement, so the Android UI reports
where it is streaming rather than claiming that a receiver is connected.

### RTP Header

| Field | Value |
|---|---|
| Version | 2 |
| Padding / Extension / CSRC | 0 |
| Marker | 0 |
| Payload Type | 127 by default (dynamic range) |
| Sequence | Random 16-bit start, incremented once per packet |
| Timestamp | Random 32-bit start, incremented by 960 per packet |
| SSRC | Random 32-bit value per stream |
| Byte order | Network byte order (big endian) |

The Opus RTP clock is always 48 kHz. A 20 ms packet therefore advances the
timestamp by `48000 * 0.020 = 960`, including when Opus internally selects a
narrower audio bandwidth. Sequence numbers and timestamps wrap naturally.

### RTP Audio Parameters

| Parameter | Value |
|---|---|
| Input | 48 kHz, mono, PCM16 |
| Opus application | VOIP |
| Frame duration | 20 ms / 960 samples |
| Bitrate | 16, 24, 32, 48, 64, 96, or 128 kbps |
| Bandwidth | Auto, Wideband (8 kHz), or Fullband (20 kHz) |
| Default UDP port | 38472 |

Bitrate and bandwidth can change between Opus packets. No RTP signaling or
receiver reconfiguration is required.

### PipeWire Receiver

Install `linux/pipewire/opendroidmic-rtp.conf` as:

```bash
mkdir -p ~/.config/pipewire/pipewire.conf.d
cp linux/pipewire/opendroidmic-rtp.conf \
  ~/.config/pipewire/pipewire.conf.d/90-opendroidmic-rtp.conf
systemctl --user restart pipewire pipewire-pulse
```

The supplied configuration listens on UDP 38472, decodes Opus, and exposes
`OpenDroidMic RTP` as an `Audio/Source`. It uses `sess.ignore-ssrc = true` so a
new phone stream can use a newly randomized SSRC without reloading PipeWire.

RTP mode has no authentication or encryption. Use it only on a trusted LAN and
do not expose UDP 38472 to the public internet.
