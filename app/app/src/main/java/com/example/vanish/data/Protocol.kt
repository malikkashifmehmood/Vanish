package com.example.vanish.data

import org.json.JSONObject

/**
 * Everything the app knows about the wire protocol lives in this one file.
 * It follows docs/PROTOCOL.md. If the class protocol review changes a rule, change it HERE only.
 */
object Protocol {
    const val VERSION = 1
    const val TTL_MS = 30_000L

    /** Relay address used before the user types another one. 10.0.2.2 = your laptop, seen from the emulator. */
    const val DEFAULT_RELAY_URL = "ws://10.0.2.2:8000/ws"

    const val MAX_TEXT = 500
    const val MAX_NAME = 20

    const val HEARTBEAT_MS = 5_000L
    const val HEARTBEAT_TIMEOUT_MS = 12_000L

    /** A message that waited offline longer than this is dropped, never sent. See docs/PROTOCOL.md. */
    const val MAX_QUEUE_AGE_MS = TTL_MS

    /** How long a "not sent" notice stays on screen. */
    const val DROPPED_NOTICE_MS = 8_000L

    /** Keep a processed id this long after expiry so a replay is still recognised. */
    const val SEEN_GRACE_MS = 60_000L

    // ---- client -> relay ----
    fun hello(deviceId: String, name: String): String = JSONObject()
        .put("type", "hello").put("version", VERSION)
        .put("device_id", deviceId).put("name", name).toString()

    fun msg(id: String, sender: String, text: String): String = JSONObject()
        .put("type", "msg").put("id", id).put("sender", sender).put("text", text).toString()

    fun delivered(id: String): String = JSONObject().put("type", "delivered").put("id", id).toString()

    fun ping(): String = JSONObject().put("type", "ping").toString()
}

/** Message lifecycle. EXPIRED is not stored: an expired message is deleted. */
object State {
    const val WAITING = "WAITING"       // written, not yet confirmed by the relay (offline queue)
    const val SENT = "SENT"             // relay acknowledged it and stamped sent_at / expires_at
    const val DELIVERED = "DELIVERED"   // mine: another phone confirmed it / theirs: arrived on this phone
    const val DISPLAYED = "DISPLAYED"   // theirs: drawn on screen
    const val DROPPED = "DROPPED"       // never sent because it waited too long offline
}
