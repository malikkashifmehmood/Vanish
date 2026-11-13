# Vanish protocol (version 1)

> **Read this first.** The class specification was not available when this app was written. This
> file is a reconstruction from the items the assignment lists (connect and identify, message format
> with `id, sender, text, sent_at, expires_at`, delivery confirmation, expiry announcements, heartbeat
> and reconnection). **Where the class protocol differs, the class protocol wins.** Compare it with
> this file in the Day 2 protocol review. Everything the app knows about the wire format is in
> `app/app/src/main/java/com/example/vanish/data/Protocol.kt` (and the relay's in `relay/relay_core.py`).
> Do not change it on your own: raise differences in the review.

Transport: one WebSocket, text frames, one JSON object per frame. Default path `/ws`.

## Client to relay
| type | fields | meaning |
|---|---|---|
| `hello` | `version`, `device_id`, `name` | first frame. `device_id` is a UUID kept per install |
| `msg` | `id`, `sender`, `text` | send. `id` is a UUID made when the message is written |
| `delivered` | `id` | "this message reached my phone" |
| `ping` | | heartbeat and clock sample |

## Relay to client
| type | fields | meaning |
|---|---|---|
| `welcome` | `version`, `server_time`, `ttl_ms` | hello accepted. `server_time` is relay time in ms |
| `msg` | `id`, `sender`, `text`, `sent_at`, `expires_at` | a message from someone else. Times are relay time, `expires_at = sent_at + 30000` |
| `ack` | `id`, `sent_at`, `expires_at` | relay accepted your `msg`. Repeated for a retry of the same id |
| `delivered` | `id`, `to` | someone confirmed your message arrived |
| `expired` | `id` | message deleted at the relay: delete it everywhere |
| `pong` | `server_time` | answer to `ping` |
| `error` | `code`, optional `id` | `bad_version`, `hello_required`, `invalid`, `duplicate_id`, `unknown_type` |

## Rules
1. **The relay decides expiry.** It stamps `sent_at` and `expires_at` with its own clock. Phones never
   use their own clock to decide when a message ends. Every phone shows `expires_at - relay_time`, so
   countdowns match across phones. A phone estimates relay time from `server_time` (welcome and pong)
   plus the monotonic clock, so a phone with a wrong wall clock still expires messages on time.
2. **Each phone also enforces expiry locally**, even when it is offline or the relay announcement is lost.
3. **Relay memory only.** Messages are deleted from relay memory at `expires_at`, never logged, never written to disk.
4. **Duplicates.** The relay re-acks a repeated `id` and never forwards it twice. A phone ignores an `id`
   it already processed (ids, not text, are remembered for 60 s after expiry).
5. **No replay of expired messages.** On `hello` the relay sends only messages that are still alive, and
   not the device's own. A phone drops any message that arrives already expired.
6. **Heartbeat.** The phone pings every 5 s. No frame for 12 s means the connection is dead: reconnect
   (back-off 1 s, 2 s, 4 s, then 8 s).
7. **Offline queue.** A message written offline is stored as `WAITING` and sent after reconnecting.
   A message that has waited longer than 30 s since it was written is **dropped, not sent** (decision below).

## Message lifecycle (client)
```
 my message:    written -> WAITING -> SENT -> DELIVERED -> (expires_at) deleted
                              \-> DROPPED (waited > 30 s, or relay said invalid) -> notice 8 s -> deleted
 their message: received -> DELIVERED -> DISPLAYED -> (expires_at) deleted
                  (arrived already expired, or duplicate id) -> ignored, never stored
```

## Decision: a message queued offline for too long
Question from Stage 4: written at 0:00, queued, network back at 0:50. Our app drops it at 0:30 and
tells the sender "Not sent". Reason: the user wrote a quick message meant to vanish about 30 s
later. Sending it 50 s late would show stale text for a further 30 s, which breaks what they expected.
Change `MAX_QUEUE_AGE_MS` in `Protocol.kt` if the class decides differently.
