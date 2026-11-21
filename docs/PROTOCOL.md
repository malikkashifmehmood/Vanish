
# Vanish Official Class Protocol (Version 1)

**Protocol status:** Official class protocol  
**Protocol version:** 1  
**Class protocol:** Sawaira's protocol

> **Read this first.** Sawaira's protocol is the official class specification. This document describes the protocol implemented by the Vanish Android application and its interaction with the shared class relay.

Vanish is an Android disappearing-chat application built with Kotlin, Jetpack Compose, Room, and OkHttp WebSocket.

The application uses the official class protocol described below. The shared class relay is responsible for assigning message timestamps and managing relay-side message expiry.

## 1. Transport

- Transport: WebSocket.
- Payload format: JSON text frames.
- Protocol version: 1.
- Shared relay endpoint: `wss://vanish-relay-sawaira.bonto.page/ws`.
- Message lifetime: 30 seconds.
- Timestamps: milliseconds since Unix epoch, assigned by the relay.

## 2. Client-to-relay messages

### Join

The client identifies itself and joins the class room.

```json
{
  "type": "join",
  "protocol": 1,
  "device_id": "unique-device-id",
  "name": "Display Name"
}
```

The display name is required and has a maximum length of 30 characters.

### Send a message

```json
{
  "type": "message",
  "id": "unique-message-id",
  "sender": "Display Name",
  "text": "Hello"
}
```

- `id` identifies the message and supports duplicate detection.
- `text` must contain between 1 and 500 characters.
- The relay uses the name associated with the joined connection as the sender identity.

### Heartbeat

```json
{
  "type": "ping"
}
```

The client sends periodic heartbeat messages to maintain the connection.

## 3. Relay-to-client messages

### Welcome

```json
{
  "type": "welcome",
  "protocol": 1,
  "server_time": 1794839291139
}
```

The client validates the protocol version and uses the relay timestamp to synchronize its countdown clock.

### Message broadcast

```json
{
  "type": "message",
  "id": "unique-message-id",
  "sender": "Display Name",
  "text": "Hello",
  "sent_at": 1794839291139,
  "expires_at": 1794839321139
}
```

The relay assigns the timestamps.

`expires_at` is 30 seconds after `sent_at`.

Messages are broadcast to joined clients, including the sender.

### Delivery confirmation

```json
{
  "type": "delivered",
  "id": "unique-message-id",
  "count": 2
}
```

The confirmation reports how many joined clients received the broadcast.

### Expiration announcement

```json
{
  "type": "expired",
  "id": "unique-message-id"
}
```

This indicates that the relay has expired the message.

### Heartbeat response

The relay responds to a heartbeat with a `pong` message.

### Error

The relay can send an `error` event to report protocol or validation problems.

## 4. Optional Presence event

The Android application includes optional handling for a relay-to-client `presence` event.

Example:

```json
{
  "type": "presence",
  "count": 3,
  "members": ["Sawaira", "Ali", "Sara"]
}
```

The intended information is:

- `count`: number of members included in the member list.
- `members`: display names of currently connected members, in relay-provided join order.

The Android client validates the event before displaying its member list. Invalid or inconsistent data is rejected.

Important compatibility rules:

- Presence is an optional relay-to-client event, not a replacement for any existing Version 1 event.
- The Android application does not send Presence events.
- Protocol version remains `1`.
- Existing join, message, delivery, expiry, heartbeat, and reconnection behavior remains unchanged.
- The application can display the member list only when it receives a valid Presence event from the relay.
- The shared class relay must actually emit this event for the feature to provide live member information. Client-side handling alone does not establish end-to-end support.

The optional event must not be treated as a required part of the shared relay's existing behavior.

## 5. Error handling

The relay can report protocol and validation errors, including:

- `bad_json`
- `bad_protocol`
- `bad_name`
- `not_joined`
- `bad_message`
- `unknown_type`

The client should not treat an invalid message as successfully delivered.

## 6. Message lifecycle

Outgoing messages follow these application states:

| State | Meaning |
|---|---|
| `WAITING` | Saved locally and waiting for a connection |
| `SENT` | Sent to the relay |
| `DELIVERED` | Delivery confirmation received |
| `DROPPED` | Message exceeded the permitted offline queue age |

Incoming messages follow these states:

| State | Meaning |
|---|---|
| `DELIVERED` | Received by the application |
| `DISPLAYED` | Displayed in the chat interface |

Messages are removed when their expiration time is reached.

## 7. Expiration and clock synchronization

The relay determines the message timestamps.

The Android application calculates its countdown using relay time rather than trusting the phone's wall clock.

The application estimates relay time using the server timestamp and Android's monotonic clock.

Both the relay and the client enforce message expiration.

This helps prevent a message from remaining visible beyond its intended lifetime merely because the phone's clock is incorrect.

## 8. Offline messages and reconnection

- Outgoing messages are saved locally before transmission.
- Messages waiting for a connection use the `WAITING` state.
- A queued message older than 30 seconds is dropped rather than sent late.
- Message IDs are reused when retrying the same queued message.
- Duplicate message IDs are handled to avoid repeated processing.
- The client reconnects using exponential backoff: 1, 2, 4, and then 8 seconds.
- Heartbeats are sent every 15 seconds.
- The connection is considered unresponsive after the 30-second heartbeat timeout.
- Messages sent while a client is disconnected are not guaranteed to be replayed after reconnection.

## 9. Data storage and deletion

The Android application uses Room to store active messages.

When a message expires, the application overwrites its text and deletes its database row. SQLite secure deletion is enabled.

The shared relay maintains active message data in memory and removes expired messages.

These application-level deletion measures do not guarantee forensic erasure from physical storage, backups, screenshots, notifications, or other external copies.

## 10. Security and privacy limitations

The shared class relay:

- Does not require a password to join.
- Can be accessed by anyone who knows its address.
- Logs message text.
- Does not provide end-to-end encryption.

The application must therefore not be presented as a secure private messaging service.

The WebSocket connection uses `wss://`, which protects transport in transit, but does not provide end-to-end encryption.

Users should avoid sending confidential or sensitive information.

## 11. Implementation references

The Android implementation is located in:

- `app/app/src/main/java/com/example/vanish/data/Protocol.kt`
- `app/app/src/main/java/com/example/vanish/data/ChatRepository.kt`
- `app/app/src/main/java/com/example/vanish/data/ServerClock.kt`
- `app/app/src/main/java/com/example/vanish/data/MessageDao.kt`
- `app/app/src/main/java/com/example/vanish/ui/ChatScreen.kt`

This document describes the official class protocol as implemented by Vanish. The Android application and relay behavior should remain compatible with this specification.
