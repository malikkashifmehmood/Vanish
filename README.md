# Vanish: 30-second disappearing class chat

Android app (Kotlin, Jetpack Compose, Room, OkHttp WebSocket) plus a small Python relay for local testing. Built on the `field-sync` starter project (same pinned Gradle / Compose / Room versions).

**Platform:** Android, minSdk 26, targetSdk 35.

**Relay address used:** `ws://10.0.2.2:8000/ws` on the emulator (your laptop's relay). On class day, type the class relay's address on the Join screen. It is saved, no rebuild needed.

## Run it from VS Code (Windows, macOS or Linux)

One-time needs: **Android Studio installed once** (it provides the SDK, emulator and a JDK) with **one virtual device** created in Device Manager, and **Python 3** for the relay.

1. Open this folder (`vanish/`) in VS Code.
2. Press **Ctrl+Shift+B** (task "Vanish: start relay + run app on emulator").
   - One terminal starts the relay on port 8000.
   - The other builds the app, starts your emulator if none is running, installs and opens Vanish.
   - The first build downloads Gradle and libraries and takes several minutes.
3. In the app: type a name, keep the default relay address, tap **Join**.
4. Only one emulator? Run task **Relay: demo bot (fake classmate)**. It sends a message every 12 s.

Other tasks (Terminal > Run Task): *Relay: run protocol tests*, *Storage: check deleted messages*.

Terminal equivalent: `bash scripts/start-relay.sh` and `bash scripts/run-app.sh`  
(Windows: `powershell -ExecutionPolicy Bypass -File scripts\run-app.ps1`).

**Physical phone:** same Wi-Fi as the laptop, relay address `ws://<laptop-ip>:8000/ws` (the relay script prints it), allow port 8000 in the firewall, and USB debugging on for the installer.

The relay listens on `0.0.0.0:8000` to accept local network connections. Use this setup only on a trusted network for the class demonstration. It is not a production-secure public relay.

## What is where

```text
relay/    relay_core.py (protocol logic), main.py (FastAPI WebSocket), test_relay.py, demo_bot.py
app/      Android project: data/ (Protocol, ChatRepository, Room, ServerClock), ui/ (Join, Chat)
scripts/  run-app, start-relay, check_storage.py
docs/     PROTOCOL.md <- read the note at the top
```

## How it meets the brief

| Requirement | Where |
|---|---|
| Countdown from relay `expires_at`, not the phone clock | `ServerClock.kt`, `ChatScreen.kt` (`expires_at - repo.now()`) |
| Survives background, rotation, kill | Expiry is stored as an absolute time; state lives in `VanishApp`; sweep on start and every 0.5 s |
| Offline queue, exactly once | `WAITING` rows, resent on reconnect with the same ID; relay and phone both ignore repeated IDs |
| Real deletion, not hiding | Text overwritten, row deleted, `secure_delete` on, message text absent from the tested database, journal and WAL after expiry, `allowBackup=false` (`AppDatabase.kt`, `MessageDao.kt`) |
| No replay of expired messages | Relay sends only live messages on reconnect; phone drops late arrivals and duplicates |
| Lifecycle states | `Protocol.kt` `State`, diagram in `docs/PROTOCOL.md` |

## Protocol status

`docs/PROTOCOL.md` contains a reconstructed protocol based on the assignment requirements because no separate class-wide specification was provided.

It has not been confirmed as the class-approved protocol. Compare it with any protocol agreed upon by the class before claiming class-wide compatibility. If the class later agrees on different message fields, event names or timing rules, update the implementation to match that agreement.

## Demo checklist (Stage 3 to 5)

- **Matching countdowns:** two emulators (or emulator + phone). Send a message, compare.
- **Wrong phone clock:** emulator Settings > Date & time: switch off automatic time, set it 5 minutes off. The header shows `relay clock +/-300 s`, countdowns stay right.
- **Offline send:** emulator airplane mode on, send, airplane mode off within 30 s: it arrives once. Wait more than 30 s offline: "Not sent" notice.
- **Background / rotate / kill:** do each mid-countdown; nothing should survive past 0.
- **Storage proof:** run `Storage: check deleted messages` with your text as argument while the message is visible (text FOUND), and again after it expired (rows 0, text not found).

## Testing and verification

The following checks were performed during development:

- Android debug build completed successfully.
- Application installed and launched on the Pixel 6 Android emulator.
- Android unit tests passed.
- Relay tests: 11 passed.
- Two-way messaging tested using the demo bot.
- Offline queue and reconnection tested; the queued message was delivered once in the tested scenario.
- Storage check after expiry showed zero message rows.
- The tested message text was absent from the raw database, journal and WAL file bytes after expiry. A small WAL file still existed.
- Android lint completed with zero errors and seven warnings.

These results describe the tests performed; they do not guarantee that every possible device, network or failure scenario has been tested.

## Honest limits (for your design note)

Screenshots and photos of another phone, people copying the text, the relay's memory during the 30 s, a phone that is off when a message expires (it is deleted at next start, not at the moment of expiry), flash storage that may keep old blocks, and a rooted or modified app.

The app can only promise to delete messages within its own functioning software and controlled storage. It cannot prevent every form of external capture or guarantee forensic erasure from physical storage.

## Security considerations

- The relay keeps active messages in memory and does not intentionally log or write message content to disk.
- Message expiry is enforced by both the relay and the client.
- Message IDs help prevent duplicate processing.
- Android backup is disabled and backup exclusion rules are configured.
- SQLite `secure_delete` is enabled.
- The local development relay uses unencrypted `ws://` transport. This is suitable only for controlled local testing. A production deployment would require encrypted `wss://` transport, authentication and additional security controls.
- The relay binds to `0.0.0.0:8000` for local network testing. Use it only on a trusted network and do not expose it as a public service.

## AI assistance and acknowledgements

The original code generation was attributed to Claude (Anthropic), using the assignment text and the `field-sync` starter project.

ChatGPT was also used during subsequent debugging, security review, build and test verification, and documentation improvements.

Libraries: Jetpack Compose, Room, OkHttp, kotlinx.coroutines, FastAPI and Uvicorn.

All submitted code should be understood by the project team. The design note, test log and individual reflection should accurately describe each member's contribution.