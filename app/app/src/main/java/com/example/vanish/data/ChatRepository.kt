package com.example.vanish.data

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class Connection { OFFLINE, CONNECTING, ONLINE }

/**
 * One instance per process (created in VanishApp), so the connection, the offline queue and the
 * expiry sweeper survive screen rotation and activity restarts.
 *
 * Message lifecycle (see docs/PROTOCOL.md):
 *   written -> WAITING -> SENT -> DELIVERED          (my messages)
 *   received -> DELIVERED -> DISPLAYED               (their messages)
 *   ... -> expired = row deleted;  WAITING too long or rejected -> DROPPED, then deleted
 */
class ChatRepository(context: Context) {
    private val appContext = context.applicationContext
    val settings = Settings(appContext)
    val clock = ServerClock(settings.prefs)
    private val db = AppDatabase.build(appContext)
    private val dao = db.messageDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // the heartbeat below detects dead connections
        .build()

    private val _name = MutableStateFlow(settings.name)
    val name: StateFlow<String?> = _name
    private val _connection = MutableStateFlow(Connection.OFFLINE)
    val connection: StateFlow<Connection> = _connection
    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice

    val messages: Flow<List<MessageEntity>> = dao.observeAll()
    val storedCount: Flow<Int> = dao.observeCount()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var welcomed = false
    @Volatile private var gotWelcome = false
    @Volatile private var helloSentAt = 0L
    @Volatile private var pingSentAt = 0L
    private val nudge = Channel<Unit>(Channel.CONFLATED)

    /** Relay time in ms. The UI computes every countdown from this and expires_at. */
    fun now(): Long = clock.now()

    // ------------------------------------------------------------------ public actions

    /** Returns an error message, or null when joined. */
    fun join(name: String, relayUrl: String): String? {
        val n = name.trim()
        if (n.length < 2 || n.length > Protocol.MAX_NAME) return "Name must be 2 to ${Protocol.MAX_NAME} characters"
        val url = relayUrl.trim()
        if (!(url.startsWith("ws://") || url.startsWith("wss://"))) return "Relay address must start with ws:// or wss://"
        settings.relayUrl = url
        settings.name = n
        _name.value = n
        socket?.cancel()          // restart the session with the new settings
        nudge.trySend(Unit)
        return null
    }

    /** Forget the name and wipe everything stored on this phone. */
    fun leave() {
        settings.name = null
        _name.value = null
        socket?.cancel()
        scope.launch {
            db.withTransaction { dao.wipeAll(); dao.deleteAll(); dao.deleteAllSeen() }
        }
    }

    /** Write a message. It is stored as WAITING first, so it survives being offline or the app being killed. */
    fun send(text: String) {
        val sender = settings.name ?: return
        val t = text.trim().take(Protocol.MAX_TEXT)
        if (t.isEmpty()) return
        scope.launch {
            val m = MessageEntity(
                id = UUID.randomUUID().toString(), sender = sender, text = t,
                sentAt = 0L, expiresAt = 0L, mine = true, state = State.WAITING,
                createdAt = System.currentTimeMillis()
            )
            dao.insert(m)
            val ws = socket
            if (ws != null && welcomed) ws.send(Protocol.msg(m.id, sender, t))
        }
    }

    fun markDisplayed(id: String) {
        scope.launch { dao.markDisplayed(id) }
    }

    /** Called from Activity.onStart: delete anything that expired while we were away, and reconnect if needed. */
    fun onForeground() {
        scope.launch { sweepOnce() }
        if (_connection.value != Connection.ONLINE) nudge.trySend(Unit)
    }

    // ------------------------------------------------------------------ expiry sweeper

    private suspend fun sweepOnce() {
        val now = clock.now()
        db.withTransaction {
            dao.wipeExpired(now)      // overwrite the text first ...
            dao.deleteExpired(now)    // ... then delete the row
            dao.forgetOld(now)
        }
        // A queued message that waited longer than its whole lifetime is never sent.
        val wall = System.currentTimeMillis()
        for (m in dao.waiting()) {
            if (wall - m.createdAt > Protocol.MAX_QUEUE_AGE_MS) dao.drop(m.id, now + Protocol.DROPPED_NOTICE_MS)
        }
    }

    private suspend fun sweeperLoop() {
        while (true) {
            try {
                sweepOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // never let one failed sweep stop the sweeper
            }
            delay(500)
        }
    }

    // ------------------------------------------------------------------ connection

    private suspend fun connectionLoop() {
        var backoff = 1_000L
        while (true) {
            val myName = settings.name
            if (myName == null) {
                _connection.value = Connection.OFFLINE
                delay(300)
                continue
            }
            val ok = try {
                runSession(myName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _connection.value = Connection.OFFLINE
            backoff = if (ok) 1_000L else minOf(backoff * 2, 8_000L)
            withTimeoutOrNull(backoff) { nudge.receive() }
        }
    }

    /** Runs one WebSocket session until it ends. Returns true if the relay welcomed us. */
    private suspend fun runSession(myName: String): Boolean = coroutineScope {
        val request = try {
            Request.Builder().url(settings.relayUrl).build()
        } catch (e: IllegalArgumentException) {
            _notice.value = "Bad relay address"
            return@coroutineScope false
        }
        _connection.value = Connection.CONNECTING
        clock.newConnection()
        welcomed = false
        gotWelcome = false

        val closed = CompletableDeferred<Unit>()
        val inbox = Channel<String>(Channel.UNLIMITED)   // keeps frames in order
        val lastRx = AtomicLong(SystemClock.elapsedRealtime())

        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                helloSentAt = SystemClock.elapsedRealtime()
                webSocket.send(Protocol.hello(settings.deviceId, myName))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastRx.set(SystemClock.elapsedRealtime())
                inbox.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(Unit)
            }
        })
        socket = ws

        val consumer = launch { for (frame in inbox) handleFrame(ws, frame) }
        val heartbeat = launch {
            while (true) {
                delay(Protocol.HEARTBEAT_MS)
                if (SystemClock.elapsedRealtime() - lastRx.get() > Protocol.HEARTBEAT_TIMEOUT_MS) {
                    ws.cancel()                 // silent connection: treat as dead
                    closed.complete(Unit)
                    break
                }
                if (welcomed) {
                    pingSentAt = SystemClock.elapsedRealtime()
                    ws.send(Protocol.ping())
                }
            }
        }

        try {
            closed.await()
        } finally {
            socket = null
            welcomed = false
            ws.cancel()
            inbox.close()
            consumer.cancel()
            heartbeat.cancel()
        }
        gotWelcome
    }

    // ------------------------------------------------------------------ incoming frames

    private suspend fun handleFrame(ws: WebSocket, frame: String) {
        val o = try {
            JSONObject(frame)
        } catch (e: JSONException) {
            return
        }
        when (o.optString("type")) {
            "welcome" -> onWelcome(ws, o)
            "pong" -> if (o.has("server_time")) {
                clock.onSample(o.optLong("server_time"), pingSentAt, SystemClock.elapsedRealtime())
            }
            "msg" -> onIncoming(ws, o)
            "ack" -> onAck(o)
            "delivered" -> { dao.markMineDelivered(o.optString("id")) }
            "expired" -> removeNow(o.optString("id"))
            "error" -> onError(o)
        }
    }

    private suspend fun onWelcome(ws: WebSocket, o: JSONObject) {
        if (o.optInt("version") != Protocol.VERSION || !o.has("server_time")) {
            _notice.value = "Relay protocol version ${o.optInt("version")} does not match this app (${Protocol.VERSION})"
            ws.close(1000, null)
            return
        }
        clock.onSample(o.optLong("server_time"), helloSentAt, SystemClock.elapsedRealtime())
        gotWelcome = true
        welcomed = true
        _notice.value = ""
        _connection.value = Connection.ONLINE
        pingSentAt = SystemClock.elapsedRealtime()
        ws.send(Protocol.ping())          // a second clock sample right away
        flushQueue(ws)
    }

    /** Send everything that waited offline, oldest first. The relay ignores a repeated id, so a retry is safe. */
    private suspend fun flushQueue(ws: WebSocket) {
        val wall = System.currentTimeMillis()
        val now = clock.now()
        for (m in dao.waiting()) {
            if (wall - m.createdAt > Protocol.MAX_QUEUE_AGE_MS) {
                dao.drop(m.id, now + Protocol.DROPPED_NOTICE_MS)
            } else {
                ws.send(Protocol.msg(m.id, m.sender, m.text))
            }
        }
    }

    private suspend fun onIncoming(ws: WebSocket, o: JSONObject) {
        val id = o.optString("id")
        val text = o.optString("text")
        val expiresAt = o.optLong("expires_at")
        if (id.isEmpty() || text.isEmpty() || expiresAt <= 0L) return

        val now = clock.now()
        val stored = db.withTransaction {
            if (dao.seenCount(id) > 0 || dao.byId(id) != null) {
                false                                   // duplicate id: ignored
            } else {
                dao.markSeen(SeenId(id, expiresAt + Protocol.SEEN_GRACE_MS))
                if (expiresAt <= now) {
                    false                               // arrived already expired: remembered, never shown
                } else {
                    dao.insert(
                        MessageEntity(
                            id = id, sender = o.optString("sender"), text = text,
                            sentAt = o.optLong("sent_at"), expiresAt = expiresAt,
                            mine = false, state = State.DELIVERED,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    true
                }
            }
        }
        if (stored) ws.send(Protocol.delivered(id))
    }

    private suspend fun onAck(o: JSONObject) {
        val id = o.optString("id")
        val expiresAt = o.optLong("expires_at")
        if (id.isEmpty() || expiresAt <= 0L) return
        if (expiresAt <= clock.now()) {
            removeNow(id)                               // confirmed too late, it has already expired
            return
        }
        dao.stamp(id, o.optLong("sent_at"), expiresAt)
    }

    private suspend fun onError(o: JSONObject) {
        val id = o.optString("id")
        when (val code = o.optString("code")) {
            "invalid", "duplicate_id" -> if (id.isNotEmpty()) dao.drop(id, clock.now() + Protocol.DROPPED_NOTICE_MS)
            "bad_version" -> _notice.value = "Relay rejected protocol version ${Protocol.VERSION}"
            else -> _notice.value = "Relay error: $code"
        }
    }

    /** The relay announced expiry (or the ack came too late): wipe the text, then delete the row. */
    private suspend fun removeNow(id: String) {
        if (id.isEmpty()) return
        db.withTransaction { dao.wipe(id); dao.deleteById(id) }
    }

    init {
        // Started last, after every property above exists.
        scope.launch { sweeperLoop() }
        scope.launch { connectionLoop() }
    }
}
