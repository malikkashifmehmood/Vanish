"""
Vanish relay: pure protocol logic (no sockets, no disk, no logging).

This is a LOCAL STAND-IN for the class relay, so you can develop and demo on
your own laptop. On class day, point the app at the class relay address instead.
It follows docs/PROTOCOL.md. If the class protocol differs, the class protocol wins.

Properties the assignment asks the relay to have:
  * messages live in memory only, and are deleted 30 s after they were sent
  * message content is never logged and never written to disk
  * the RELAY stamps sent_at / expires_at (clients must not trust their own clock)
  * a repeated message id is acknowledged again but never forwarded twice
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any, Callable, Optional

PROTOCOL_VERSION = 1
TTL_MS = 30_000          # message lifetime, counted from sent_at
DEDUP_MS = 120_000       # how long a message id is remembered after it was sent
MAX_TEXT = 500
MAX_NAME = 20
MAX_ID = 64


@dataclass
class Out:
    """One frame the transport layer must send to one connection."""
    to: str
    payload: dict
    close: bool = False   # close the connection after sending


@dataclass
class _Client:
    name: str
    device_id: str


@dataclass
class _Msg:
    id: str
    sender_device: str
    sender_name: str
    text: str
    sent_at: int
    expires_at: int


@dataclass
class _Seen:
    device_id: str
    sent_at: int
    expires_at: int
    forget_at: int


class RelayCore:
    def __init__(self, clock: Callable[[], float] = time.time):
        self._clock = clock
        self.clients: dict[str, _Client] = {}
        self.messages: dict[str, _Msg] = {}      # id -> message (content, memory only)
        self._seen: dict[str, _Seen] = {}        # id -> stamps only, no content

    # ---------- helpers ----------
    def now_ms(self) -> int:
        return int(self._clock() * 1000)

    @staticmethod
    def _err(conn: str, code: str, msg_id: Optional[str] = None, close: bool = False) -> Out:
        payload = {"type": "error", "code": code}
        if msg_id:
            payload["id"] = msg_id
        return Out(conn, payload, close)

    @staticmethod
    def _msg_payload(m: _Msg) -> dict:
        return {
            "type": "msg", "id": m.id, "sender": m.sender_name, "text": m.text,
            "sent_at": m.sent_at, "expires_at": m.expires_at,
        }

    def _conns_of_device(self, device_id: str) -> list[str]:
        return [c for c, cl in self.clients.items() if cl.device_id == device_id]

    # ---------- inbound ----------
    def on_message(self, conn: str, data: Any) -> list[Out]:
        if not isinstance(data, dict):
            return [self._err(conn, "invalid")]
        kind = data.get("type")
        if kind == "hello":
            return self._hello(conn, data)
        client = self.clients.get(conn)
        if client is None:
            return [self._err(conn, "hello_required", close=True)]
        if kind == "msg":
            return self._msg(conn, client, data)
        if kind == "delivered":
            return self._delivered(client, data)
        if kind == "ping":
            return [Out(conn, {"type": "pong", "server_time": self.now_ms()})]
        return [self._err(conn, "unknown_type")]

    def on_disconnect(self, conn: str) -> None:
        self.clients.pop(conn, None)

    def _hello(self, conn: str, data: dict) -> list[Out]:
        if data.get("version") != PROTOCOL_VERSION:
            return [self._err(conn, "bad_version", close=True)]
        device_id, name = data.get("device_id"), data.get("name")
        if not (isinstance(device_id, str) and 0 < len(device_id) <= MAX_ID):
            return [self._err(conn, "invalid", close=True)]
        if not isinstance(name, str) or not (0 < len(name.strip()) <= MAX_NAME):
            return [self._err(conn, "invalid", close=True)]

        self.clients[conn] = _Client(name.strip(), device_id)
        now = self.now_ms()
        outs = [Out(conn, {
            "type": "welcome", "version": PROTOCOL_VERSION,
            "server_time": now, "ttl_ms": TTL_MS,
        })]
        # Messages still alive that this device has not sent itself. Expired ones are never replayed.
        alive = sorted(
            (m for m in self.messages.values()
             if m.expires_at > now and m.sender_device != device_id),
            key=lambda m: m.sent_at,
        )
        outs += [Out(conn, self._msg_payload(m)) for m in alive]
        return outs

    def _msg(self, conn: str, client: _Client, data: dict) -> list[Out]:
        msg_id, text = data.get("id"), data.get("text")
        if not (isinstance(msg_id, str) and 0 < len(msg_id) <= MAX_ID):
            return [self._err(conn, "invalid")]
        if not isinstance(text, str) or not (0 < len(text.strip()) <= MAX_TEXT):
            return [self._err(conn, "invalid", msg_id)]

        seen = self._seen.get(msg_id)
        if seen is not None:
            if seen.device_id != client.device_id:
                return [self._err(conn, "duplicate_id", msg_id)]
            # A retry of a message we already accepted: confirm again, never forward again.
            return [Out(conn, {"type": "ack", "id": msg_id,
                               "sent_at": seen.sent_at, "expires_at": seen.expires_at})]

        now = self.now_ms()
        m = _Msg(msg_id, client.device_id, client.name, text.strip(), now, now + TTL_MS)
        self.messages[msg_id] = m
        self._seen[msg_id] = _Seen(client.device_id, m.sent_at, m.expires_at, now + DEDUP_MS)

        outs = [Out(conn, {"type": "ack", "id": m.id, "sent_at": m.sent_at, "expires_at": m.expires_at})]
        payload = self._msg_payload(m)
        for c, cl in self.clients.items():
            if cl.device_id != client.device_id:
                outs.append(Out(c, payload))
        return outs

    def _delivered(self, client: _Client, data: dict) -> list[Out]:
        m = self.messages.get(data.get("id"))
        if m is None or m.sender_device == client.device_id:
            return []
        note = {"type": "delivered", "id": m.id, "to": client.name}
        return [Out(c, note) for c in self._conns_of_device(m.sender_device)]

    # ---------- timer ----------
    def sweep(self) -> list[Out]:
        """Call a few times per second. Deletes expired messages and announces it."""
        now = self.now_ms()
        outs: list[Out] = []
        for mid in [i for i, m in self.messages.items() if m.expires_at <= now]:
            del self.messages[mid]                      # content is gone from memory here
            note = {"type": "expired", "id": mid}
            outs += [Out(c, note) for c in self.clients]
        for mid in [i for i, s in self._seen.items() if s.forget_at <= now]:
            del self._seen[mid]
        return outs
