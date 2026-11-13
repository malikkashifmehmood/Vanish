package com.example.vanish.data

import android.content.SharedPreferences
import android.os.SystemClock

/**
 * The relay's clock, as best this phone can estimate it.
 *
 * expires_at is relay time. The phone's own wall clock may be minutes wrong (or changed by the user),
 * so every expiry decision uses now() from here, never System.currentTimeMillis().
 *
 * While connected: relay time = anchor + monotonic time passed since the anchor. The monotonic clock
 * ignores wall-clock changes, so changing the phone's time during a countdown does nothing.
 * Before the first sync of this process: last known offset + wall clock (best effort after a restart).
 */
class ServerClock(private val prefs: SharedPreferences) {
    @Volatile private var synced = false
    @Volatile private var anchorServer = 0L
    @Volatile private var anchorElapsed = 0L
    @Volatile private var bestRtt = Long.MAX_VALUE
    @Volatile private var storedOffset = prefs.getLong(KEY_OFFSET, 0L)

    fun now(): Long =
        if (synced) anchorServer + (SystemClock.elapsedRealtime() - anchorElapsed)
        else System.currentTimeMillis() + storedOffset

    /** relay time minus phone wall-clock time. Shown on screen so you can prove the clock fix works. */
    fun offsetMs(): Long = now() - System.currentTimeMillis()

    /** Call when a new connection starts, so one old low-latency sample does not block fresh ones. */
    fun newConnection() { bestRtt = Long.MAX_VALUE }

    /**
     * @param serverTime relay time carried by a welcome or pong frame
     * @param sentElapsed SystemClock.elapsedRealtime() when the request was sent
     * @param recvElapsed SystemClock.elapsedRealtime() when the answer arrived
     */
    @Synchronized
    fun onSample(serverTime: Long, sentElapsed: Long, recvElapsed: Long) {
        val rtt = (recvElapsed - sentElapsed).coerceAtLeast(0)
        // Keep samples that are about as fast as the best one: they have the least error.
        if (synced && rtt > bestRtt + 50) return
        bestRtt = minOf(bestRtt, rtt)
        anchorServer = serverTime + rtt / 2
        anchorElapsed = recvElapsed
        synced = true
        storedOffset = anchorServer - System.currentTimeMillis()
        prefs.edit().putLong(KEY_OFFSET, storedOffset).apply()
    }

    private companion object { const val KEY_OFFSET = "clock_offset_ms" }
}
