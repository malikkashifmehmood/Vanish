package com.example.vanish.data

import android.content.Context
import java.util.UUID

class Settings(context: Context) {
    val prefs = context.getSharedPreferences("vanish", Context.MODE_PRIVATE)

    /** Stable id for this install. Sent in hello so the relay can tell devices apart. */
    val deviceId: String = prefs.getString("device_id", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

    var name: String?
        get() = prefs.getString("name", null)
        set(v) { prefs.edit().apply { if (v == null) remove("name") else putString("name", v) }.apply() }

    var relayUrl: String
        get() = prefs.getString("relay_url", null) ?: Protocol.DEFAULT_RELAY_URL
        set(v) { prefs.edit().putString("relay_url", v).apply() }
}
