"""Protocol tests for the relay core. Run: python -m unittest test_relay -v  (no packages needed)"""
import unittest

from relay_core import TTL_MS, RelayCore


class FakeClock:
    def __init__(self):
        self.t = 1_000.0

    def __call__(self):
        return self.t


def hello(core, conn, device, name):
    return core.on_message(conn, {"type": "hello", "version": 1, "device_id": device, "name": name})


def msgs_to(outs, conn, kind):
    return [o.payload for o in outs if o.to == conn and o.payload["type"] == kind]


class RelayTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.core = RelayCore(self.clock)
        hello(self.core, "a", "dev-a", "Alice")
        hello(self.core, "b", "dev-b", "Bob")

    def test_welcome_carries_server_time(self):
        outs = hello(self.core, "c", "dev-c", "Cy")
        w = msgs_to(outs, "c", "welcome")[0]
        self.assertEqual(w["server_time"], 1_000_000)
        self.assertEqual(w["ttl_ms"], TTL_MS)

    def test_bad_version_closes(self):
        outs = self.core.on_message("x", {"type": "hello", "version": 99, "device_id": "d", "name": "n"})
        self.assertTrue(outs[0].close)
        self.assertEqual(outs[0].payload["code"], "bad_version")

    def test_message_before_hello_rejected(self):
        outs = self.core.on_message("x", {"type": "msg", "id": "1", "text": "hi"})
        self.assertEqual(outs[0].payload["code"], "hello_required")

    def test_relay_stamps_times_and_does_not_echo_to_sender(self):
        outs = self.core.on_message("a", {"type": "msg", "id": "m1", "sender": "Alice", "text": "hi"})
        ack = msgs_to(outs, "a", "ack")[0]
        self.assertEqual(ack["expires_at"], ack["sent_at"] + TTL_MS)
        self.assertEqual(msgs_to(outs, "a", "msg"), [])
        got = msgs_to(outs, "b", "msg")[0]
        self.assertEqual((got["id"], got["sender"], got["text"]), ("m1", "Alice", "hi"))
        self.assertEqual(got["expires_at"], ack["expires_at"])

    def test_duplicate_id_acked_again_never_forwarded_again(self):
        first = self.core.on_message("a", {"type": "msg", "id": "m1", "text": "hi"})
        self.clock.t += 5
        again = self.core.on_message("a", {"type": "msg", "id": "m1", "text": "hi"})
        self.assertEqual(msgs_to(again, "b", "msg"), [])
        self.assertEqual(msgs_to(again, "a", "ack")[0], msgs_to(first, "a", "ack")[0])

    def test_id_collision_between_devices_rejected(self):
        self.core.on_message("a", {"type": "msg", "id": "m1", "text": "hi"})
        outs = self.core.on_message("b", {"type": "msg", "id": "m1", "text": "other"})
        self.assertEqual(outs[0].payload["code"], "duplicate_id")

    def test_expiry_announced_and_content_deleted(self):
        self.core.on_message("a", {"type": "msg", "id": "m1", "text": "secret"})
        self.clock.t += 29
        self.assertEqual(self.core.sweep(), [])
        self.clock.t += 1.5
        outs = self.core.sweep()
        self.assertEqual({o.to for o in outs}, {"a", "b"})
        self.assertTrue(all(o.payload == {"type": "expired", "id": "m1"} for o in outs))
        self.assertNotIn("m1", self.core.messages)

    def test_reconnect_gets_live_messages_only_never_expired_or_own(self):
        self.core.on_message("a", {"type": "msg", "id": "old", "text": "old"})
        self.clock.t += 20
        self.core.on_message("a", {"type": "msg", "id": "new", "text": "new"})
        self.clock.t += 15            # "old" is now 35 s old, "new" is 15 s old
        self.core.sweep()
        outs = hello(self.core, "b2", "dev-b", "Bob")
        self.assertEqual([m["id"] for m in msgs_to(outs, "b2", "msg")], ["new"])
        outs = hello(self.core, "a2", "dev-a", "Alice")
        self.assertEqual(msgs_to(outs, "a2", "msg"), [])

    def test_delivered_forwarded_to_sender(self):
        self.core.on_message("a", {"type": "msg", "id": "m1", "text": "hi"})
        outs = self.core.on_message("b", {"type": "delivered", "id": "m1"})
        self.assertEqual(msgs_to(outs, "a", "delivered"), [{"type": "delivered", "id": "m1", "to": "Bob"}])

    def test_invalid_text_rejected(self):
        for bad in ["", "   ", "x" * 501, 5, None]:
            outs = self.core.on_message("a", {"type": "msg", "id": "z", "text": bad})
            self.assertEqual(outs[0].payload["code"], "invalid")

    def test_ping_pong(self):
        outs = self.core.on_message("a", {"type": "ping"})
        self.assertEqual(outs[0].payload, {"type": "pong", "server_time": 1_000_000})


if __name__ == "__main__":
    unittest.main()
