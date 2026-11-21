
package com.example.vanish.data

import org.json.JSONObject

/**
 * Wire protocol for Sawaira's official Vanish Relay Protocol v1.
 * See docs/PROTOCOL.md.
 */
object Protocol {
    const val VERSION = 1
    const val TTL_MS = 30_000L

    /** Public class relay. */
    const val DEFAULT_RELAY_URL =
        "wss://vanish-relay-sawaira.bonto.page/ws"

    const val MAX_TEXT = 500
    const val MAX_NAME = 30

    // Class protocol: ping every 15 seconds, reconnect after 30 seconds without a response.
    const val HEARTBEAT_MS = 15_000L
    const val HEARTBEAT_TIMEOUT_MS = 30_000L

    /** Offline messages older than their lifetime are never sent. */
    const val MAX_QUEUE_AGE_MS = TTL_MS

    const val DROPPED_NOTICE_MS = 8_000L

    /** Keep processed IDs after expiry to reject replayed messages. */
    const val SEEN_GRACE_MS = 60_000L

    // ---- app -> relay ----

    fun join(deviceId: String, name: String): String = JSONObject()
        .put("type", "join")
        .put("protocol", VERSION)
        .put("device_id", deviceId)
        .put("name", name)
        .toString()

    fun message(id: String, sender: String, text: String): String = JSONObject()
        .put("type", "message")
        .put("id", id)
        .put("sender", sender)
        .put("text", text)
        .toString()

    fun ping(): String = JSONObject()
        .put("type", "ping")
        .toString()
}

/**
 * Optional relay-to-app presence information.
 *
 * This does not introduce a new client-to-relay event or protocol version.
 * Members retain the order supplied by the relay.
 */
data class PresenceSnapshot(
    val count: Int,
    val members: List<String>
) {
    companion object {
        /**
         * Returns null for inconsistent or invalid presence information.
         * This keeps malformed optional events away from the chat UI.
         */
        fun create(
            count: Int,
            members: List<String>
        ): PresenceSnapshot? {
            if (count < 0 || count != members.size) {
                return null
            }

            if (members.any {
                    it.isBlank() || it.length > Protocol.MAX_NAME
                }
            ) {
                return null
            }

            return PresenceSnapshot(
                count = count,
                members = members.toList()
            )
        }
    }
}

/** Message lifecycle. EXPIRED is not stored: an expired message is deleted. */
object State {
    const val WAITING = "WAITING"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val DISPLAYED = "DISPLAYED"
    const val DROPPED = "DROPPED"
}
