
# Vanish — Disappearing Chat Application

Vanish is an Android disappearing-chat application developed as a Mobile Application Development course project. It allows users to join a shared chat relay, exchange short messages, and automatically remove messages after their lifetime expires.

The application is built with Kotlin and Jetpack Compose. It uses Room for local message storage and WebSocket communication for real-time messaging.

## Project information

| Item | Details |
|---|---|
| Project | Vanish |
| Platform | Android |
| Language | Kotlin |
| UI framework | Jetpack Compose |
| Local database | Room |
| Communication | WebSocket |
| Message lifetime | 30 seconds |
| Protocol | Version 1 (Sawaira's official class protocol) |
| Shared relay | `wss://vanish-relay-sawaira.bonto.page/ws` |

## Features

- Join the shared class chat using a display name.
- Send and receive messages in real time.
- Automatically expire messages after 30 seconds.
- Display a countdown for active messages.
- Synchronize message timing using relay timestamps.
- Store active messages locally using Room.
- Queue outgoing messages while disconnected.
- Drop queued messages that exceed the permitted 30-second age.
- Reconnect automatically when the WebSocket connection is interrupted.
- Detect duplicate message IDs to avoid repeated processing.
- Display connection status and connection details.
- Confirm before leaving a chat.
- Handle optional relay-to-client Presence events and display a validated member list when such an event is received.

**Presence limitation:** The Android application can process an optional Presence event, but live member information depends on the shared relay actually emitting that event. The app does not send Presence events, and no end-to-end Presence support is claimed unless the shared relay provides it.

## Technology stack

- **Kotlin:** Main application programming language.
- **Jetpack Compose:** Android user interface.
- **Material 3:** UI components and styling.
- **Room:** Local database for active messages.
- **OkHttp WebSocket:** Real-time communication with the relay.
- **Kotlin Coroutines:** Asynchronous operations.
- **Gradle:** Android build and dependency management.

## Project structure

```text
vanish/
├── app/
│   ├── app/
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── java/com/example/vanish/
│   │   │   │   │   ├── data/
│   │   │   │   │   ├── ui/
│   │   │   │   │   └── ...
│   │   │   └── test/
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradlew.bat
├── docs/
│   ├── PROTOCOL.md
│   ├── Design_Note.pdf
│   └── Individual_Reflection.md
└── README.md
```

## Requirements

- Android Studio.
- Android SDK.
- JDK version compatible with the project's Gradle configuration.
- An Android emulator or physical Android device.
- Internet access to connect to the shared class relay.

## Running the application

### 1. Clone the repository

```powershell
git clone https://github.com/malikkashifmehmood/Vanish.git
```

### 2. Open the project

Open the cloned `vanish` folder in Android Studio.

Allow Android Studio to synchronize the Gradle project and download the required dependencies.

### 3. Run the application

Connect an Android device or start an emulator.

Run the `app` configuration from Android Studio.

The application uses the shared class relay by default:

```text
wss://vanish-relay-sawaira.bonto.page/ws
```

A public relay may take some time to respond if its hosting service is sleeping or starting up. Allow time for the initial connection.

## Optional local development relay

A separate local relay can be used for development and testing instead of the shared class relay.

For an Android emulator, the local relay address is:

```text
ws://10.0.2.2:8000/ws
```

For a physical Android device, use the development computer's LAN IP address when both devices are connected to the same Wi-Fi network.

The local relay is separate from the shared class relay. It is not required for normal use of the application with the shared relay.

## Message lifecycle

1. The user writes a message.
2. The application saves the outgoing message locally.
3. The application sends it to the relay when connected.
4. The relay assigns the message timestamps.
5. The relay broadcasts the message to joined clients.
6. The application displays the message and its remaining lifetime.
7. The message expires after 30 seconds.
8. The application removes the expired message from its active local storage.

The relay's timestamps are used for countdown synchronization so that the phone's wall clock does not determine message expiration.

## Protocol

Vanish implements version 1 of Sawaira's official class protocol.

The protocol uses JSON messages over WebSocket connections.

Principal protocol events include:

| Direction | Event | Purpose |
|---|---|---|
| Client → Relay | `join` | Join the chat |
| Relay → Client | `welcome` | Confirm the protocol and provide server time |
| Client → Relay | `message` | Submit a chat message |
| Relay → Clients | `message` | Broadcast a chat message |
| Relay → Sender | `delivered` | Confirm message delivery |
| Relay → Clients | `expired` | Announce message expiration |
| Client → Relay | `ping` | Client heartbeat |
| Relay → Client | `pong` | Relay heartbeat response |
| Relay → Client | `error` | Report protocol or validation errors |

### Optional Presence handling

The Android application also handles an optional relay-to-client `presence` event containing a member count and member names.

The client validates the event and preserves the supplied member order before displaying the member list.

Presence is not a required Version 1 event. The application does not send it, and existing protocol behavior is unchanged. Live member information is available only if the shared class relay actually emits valid Presence events.

The implementation uses:

- A 30-second message lifetime.
- Relay-assigned timestamps.
- Heartbeats every 15 seconds.
- A 30-second heartbeat timeout.
- Exponential reconnection backoff of 1, 2, 4, and 8 seconds.
- A maximum offline queue age of 30 seconds.

For complete message formats, validation rules, lifecycle details, optional Presence handling, and error handling, see [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## Testing

The Android project includes automated tests for application behavior and related components.

Run the following commands from the Gradle root directory (`app`):

### Run unit tests

```powershell
.\gradlew.bat test
```

### Build the debug APK

```powershell
.\gradlew.bat :app:assembleDebug
```

### Run Android lint

```powershell
.\gradlew.bat :app:lintDebug
```

The generated debug APK is located at:

```text
app/app/build/outputs/apk/debug/app-debug.apk
```

## Security and privacy considerations

Vanish is an educational disappearing-chat application. Its expiration features should not be interpreted as a guarantee of secure or permanent data erasure.

Important limitations:

- The shared class relay does not require a password.
- Anyone who knows the shared relay address may attempt to connect.
- The shared relay logs message text.
- The application does not implement end-to-end encryption.
- `wss://` protects communication in transit but does not prevent the relay from accessing message content.
- Messages may be copied, photographed, captured in screenshots, or exposed through external device mechanisms.
- Application-level deletion does not guarantee forensic erasure from physical storage, backups, or external copies.
- Messages sent while disconnected are not guaranteed to be delivered after reconnection if they have expired.

Users should not send confidential or sensitive information through the shared class relay.

## Documentation

Additional project documentation is available in the `docs` directory:

- **[`docs/PROTOCOL.md`](docs/PROTOCOL.md)** — Version 1 of Sawaira's official class protocol, including message formats, timestamps, expiration, heartbeat, reconnection, optional Presence handling, and error handling.
- **`docs/Design_Note.pdf`** — Project design, architecture, implementation details, testing, and security considerations.
- **[`docs/Individual_Reflection.md`](docs/Individual_Reflection.md)** — Individual project reflection covering development work, testing, documentation, and lessons learned.

## Protocol status

Vanish implements version 1 of Sawaira's official class protocol.

The Android application and shared relay must remain aligned with the official class specification. Optional client-side Presence handling does not change the protocol version or replace existing required events.

## AI assistance disclosure

AI tools were used as development assistance during parts of the project, including support with code review, debugging, documentation, and explanations.

The project implementation, testing, and final review remain the responsibility of the project developer.
