
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
 * One instance per process (created in VanishApp).
 *
 * Implements Sawaira's official Vanish Relay Protocol v1.
 * Optional Presence events are received from the relay when supported.
 *
 * Message lifecycle:
 *   written -> WAITING -> SENT -> DELIVERED
 *   received -> DELIVERED -> DISPLAYED
 *   expired = text wiped and row deleted
 */
class ChatRepository(context: Context) {

    private val appContext = context.applicationContext
    val settings = Settings(appContext)
    val clock = ServerClock(settings.prefs)

    private val db = AppDatabase.build(appContext)
    private val dao = db.messageDao()

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        // The free public relay may take up to a minute to wake.
        .connectTimeout(75, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _name = MutableStateFlow(settings.name)
    val name: StateFlow<String?> = _name

    private val _connection =
        MutableStateFlow(Connection.OFFLINE)
    val connection: StateFlow<Connection> = _connection

    // Null means the relay has not supplied a Presence update
    // for the current connection.
    private val _onlineMembers =
        MutableStateFlow<List<String>?>(null)
    val onlineMembers: StateFlow<List<String>?> = _onlineMembers

    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice

    val messages: Flow<List<MessageEntity>> = dao.observeAll()
    val storedCount: Flow<Int> = dao.observeCount()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var welcomed = false

    @Volatile
    private var gotWelcome = false

    @Volatile
    private var joinSentAt = 0L

    private val nudge = Channel<Unit>(Channel.CONFLATED)

    /** Current estimated relay time in milliseconds. */
    fun now(): Long = clock.now()

    // ------------------------------------------------------------
    // Public actions
    // ------------------------------------------------------------

    /** Returns an error message, or null when joining is requested. */
    fun join(name: String, relayUrl: String): String? {
        val n = name.trim()

        if (n.length < 2 || n.length > Protocol.MAX_NAME) {
            return "Name must be 2 to ${Protocol.MAX_NAME} characters"
        }

        val url = relayUrl.trim()

        if (!(url.startsWith("ws://") || url.startsWith("wss://"))) {
            return "Relay address must start with ws:// or wss://"
        }

        settings.relayUrl = url
        settings.name = n
        _name.value = n
        _onlineMembers.value = null

        socket?.cancel()
        nudge.trySend(Unit)

        return null
    }

    /** Forget the name and wipe everything stored on this phone. */
    fun leave() {
        settings.name = null
        _name.value = null
        _onlineMembers.value = null

        socket?.cancel()

        scope.launch {
            db.withTransaction {
                dao.wipeAll()
                dao.deleteAll()
                dao.deleteAllSeen()
            }
        }
    }

    /** Store outgoing messages before attempting transmission. */
    fun send(text: String) {
        val sender = settings.name ?: return
        val t = text.trim().take(Protocol.MAX_TEXT)

        if (t.isEmpty()) return

        scope.launch {
            val message = MessageEntity(
                id = UUID.randomUUID().toString(),
                sender = sender,
                text = t,
                sentAt = 0L,
                expiresAt = 0L,
                mine = true,
                state = State.WAITING,
                createdAt = System.currentTimeMillis()
            )

            dao.insert(message)

            val ws = socket

            if (ws != null && welcomed) {
                ws.send(
                    Protocol.message(
                        message.id,
                        sender,
                        t
                    )
                )
            }
        }
    }

    fun markDisplayed(id: String) {
        scope.launch {
            dao.markDisplayed(id)
        }
    }

    /** Called from Activity.onStart. */
    fun onForeground() {
        scope.launch {
            sweepOnce()
        }

        if (_connection.value != Connection.ONLINE) {
            nudge.trySend(Unit)
        }
    }

    // ------------------------------------------------------------
    // Expiry sweeper
    // ------------------------------------------------------------

    private suspend fun sweepOnce() {
        val now = clock.now()

        db.withTransaction {
            dao.wipeExpired(now)
            dao.deleteExpired(now)
            dao.forgetOld(now)
        }

        // An offline message older than its allowed queue lifetime
        // is dropped and its content is wiped.
        val wall = System.currentTimeMillis()

        for (message in dao.waiting()) {
            if (wall - message.createdAt > Protocol.MAX_QUEUE_AGE_MS) {
                dao.drop(
                    message.id,
                    now + Protocol.DROPPED_NOTICE_MS
                )
            }
        }
    }

    private suspend fun sweeperLoop() {
        while (true) {
            try {
                sweepOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the sweeper alive after an individual failure.
            }

            delay(500)
        }
    }

    // ------------------------------------------------------------
    // Connection and reconnection
    // ------------------------------------------------------------

    private suspend fun connectionLoop() {
        var backoff = 1_000L

        while (true) {
            val myName = settings.name

            if (myName == null) {
                _connection.value = Connection.OFFLINE
                _onlineMembers.value = null
                delay(300)
                continue
            }

            val successful = try {
                runSession(myName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }

            _connection.value = Connection.OFFLINE
            _onlineMembers.value = null

            if (successful) {
                backoff = 1_000L
            }

            val retryDelay = backoff
            backoff = minOf(backoff * 2, 8_000L)

            withTimeoutOrNull(retryDelay) {
                nudge.receive()
            }
        }
    }

    /** Runs one WebSocket session until it closes. */
    private suspend fun runSession(
        myName: String
    ): Boolean = coroutineScope {

        val request = try {
            Request.Builder()
                .url(settings.relayUrl)
                .build()
        } catch (e: IllegalArgumentException) {
            _notice.value = "Bad relay address"
            return@coroutineScope false
        }

        _connection.value = Connection.CONNECTING
        _onlineMembers.value = null

        clock.newConnection()
        welcomed = false
        gotWelcome = false

        val closed = CompletableDeferred<Unit>()
        val inbox = Channel<String>(Channel.UNLIMITED)

        val lastPong = AtomicLong(
            SystemClock.elapsedRealtime()
        )

        val ws = http.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {
                    joinSentAt = SystemClock.elapsedRealtime()

                    webSocket.send(
                        Protocol.join(
                            settings.deviceId,
                            myName
                        )
                    )
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {
                    inbox.trySend(text)
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    closed.complete(Unit)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    if (!welcomed) {
                        _notice.value =
                            "Relay connection failed: ${t.message ?: "connection error"}"
                    }

                    closed.complete(Unit)
                }
            }
        )

        socket = ws

        val consumer = launch {
            for (frame in inbox) {
                val type = try {
                    JSONObject(frame).optString("type")
                } catch (e: JSONException) {
                    ""
                }

                // Reset the heartbeat timer when the relay welcomes
                // this connection or responds to a ping.
                if (type == "pong" || type == "welcome") {
                    lastPong.set(SystemClock.elapsedRealtime())
                }

                handleFrame(ws, frame)
            }
        }

        val heartbeat = launch {
            while (true) {
                delay(Protocol.HEARTBEAT_MS)

                if (!welcomed) {
                    continue
                }

                val elapsed =
                    SystemClock.elapsedRealtime() - lastPong.get()

                if (elapsed >= Protocol.HEARTBEAT_TIMEOUT_MS) {
                    ws.cancel()
                    closed.complete(Unit)
                    break
                }

                ws.send(Protocol.ping())
            }
        }

        try {
            closed.await()
        } finally {
            socket = null
            welcomed = false
            _onlineMembers.value = null

            ws.cancel()
            inbox.close()

            consumer.cancel()
            heartbeat.cancel()
        }

        gotWelcome
    }

    // ------------------------------------------------------------
    // Incoming protocol frames
    // ------------------------------------------------------------

    private suspend fun handleFrame(
        ws: WebSocket,
        frame: String
    ) {
        val obj = try {
            JSONObject(frame)
        } catch (e: JSONException) {
            return
        }

        when (obj.optString("type")) {

            "welcome" -> onWelcome(ws, obj)

            // The class relay's pong contains no timestamp.
            "pong" -> Unit

            // The relay broadcasts complete messages to everyone,
            // including the sender.
            "message" -> onIncoming(obj)

            // Optional relay-to-app event.
            // Older relays that do not send Presence are unaffected.
            "presence" -> onPresence(obj)

            // This is a server notification to the sender.
            "delivered" -> {
                val id = obj.optString("id")
                val count = obj.optInt("count")

                // The sender is included in the count.
                // At least two joined apps means another app
                // received the broadcast.
                if (id.isNotEmpty() && count >= 2) {
                    dao.markMineDelivered(id)
                }
            }

            "expired" -> removeNow(obj.optString("id"))

            "error" -> onError(ws, obj)
        }
    }

    /**
     * Process optional Presence information from the relay.
     *
     * Presence is receive-only. This app never sends a Presence event.
     * Invalid or inconsistent events are ignored without affecting chat.
     */
    private fun onPresence(obj: JSONObject) {
        // Do not accept Presence before the relay welcomes this session.
        if (!welcomed) return

        // Accept only an actual JSON integer.
        // Reject strings, decimals, missing values, and other types.
        val count = obj.opt("count") as? Int ?: return

        val membersArray = obj.optJSONArray("members") ?: return

        val members = ArrayList<String>(membersArray.length())

        for (index in 0 until membersArray.length()) {
            val member = membersArray.opt(index) as? String ?: return
            members.add(member)
        }

        val snapshot = PresenceSnapshot.create(
            count = count,
            members = members
        ) ?: return

        // Preserve the join order provided by the relay.
        _onlineMembers.value = snapshot.members
    }

    private suspend fun onWelcome(
        ws: WebSocket,
        obj: JSONObject
    ) {
        val version = obj.optInt("protocol", -1)

        if (version != Protocol.VERSION ||
            !obj.has("server_time")
        ) {
            _notice.value =
                "Relay protocol version $version does not match this app (${Protocol.VERSION})"

            ws.close(1000, null)
            return
        }

        clock.onSample(
            obj.optLong("server_time"),
            joinSentAt,
            SystemClock.elapsedRealtime()
        )

        gotWelcome = true
        welcomed = true

        _notice.value = ""
        _connection.value = Connection.ONLINE

        // Send the first heartbeat after successful welcome.
        ws.send(Protocol.ping())

        flushQueue(ws)
    }

    /** Send queued messages oldest first. */
    private suspend fun flushQueue(ws: WebSocket) {
        val wall = System.currentTimeMillis()
        val now = clock.now()

        for (message in dao.waiting()) {
            if (wall - message.createdAt > Protocol.MAX_QUEUE_AGE_MS) {
                dao.drop(
                    message.id,
                    now + Protocol.DROPPED_NOTICE_MS
                )
            } else {
                ws.send(
                    Protocol.message(
                        message.id,
                        message.sender,
                        message.text
                    )
                )
            }
        }
    }

    /**
     * Process the relay's broadcast message.
     *
     * For our own message, update the existing WAITING row using
     * sent_at and expires_at.
     *
     * For another person's message, insert a new received row.
     */
    private suspend fun onIncoming(obj: JSONObject) {
        val id = obj.optString("id")
        val text = obj.optString("text")
        val sender = obj.optString("sender")
        val sentAt = obj.optLong("sent_at")
        val expiresAt = obj.optLong("expires_at")

        if (id.isEmpty() ||
            text.isEmpty() ||
            sentAt <= 0L ||
            expiresAt <= 0L ||
            expiresAt <= sentAt
        ) {
            return
        }

        val now = clock.now()

        db.withTransaction {
            val existing = dao.byId(id)

            if (existing != null) {
                // Our own message already exists as WAITING.
                // The relay broadcast confirms its timestamps.
                if (existing.mine) {
                    if (expiresAt <= now) {
                        dao.wipe(id)
                        dao.deleteById(id)
                    } else {
                        dao.markSeen(
                            SeenId(
                                id,
                                expiresAt + Protocol.SEEN_GRACE_MS
                            )
                        )

                        dao.stamp(
                            id,
                            sentAt,
                            expiresAt
                        )
                    }
                }

                // Existing received message: duplicate.
                return@withTransaction
            }

            // Ignore a previously processed message ID.
            if (dao.seenCount(id) > 0) {
                return@withTransaction
            }

            dao.markSeen(
                SeenId(
                    id,
                    expiresAt + Protocol.SEEN_GRACE_MS
                )
            )

            if (expiresAt <= now) {
                return@withTransaction
            }

            dao.insert(
                MessageEntity(
                    id = id,
                    sender = sender,
                    text = text,
                    sentAt = sentAt,
                    expiresAt = expiresAt,
                    mine = false,
                    state = State.DELIVERED,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun onError(
        ws: WebSocket,
        obj: JSONObject
    ) {
        val code = obj.optString("code")
        val message = obj.optString("message")

        _notice.value = if (message.isNotBlank()) {
            "Relay error: $message"
        } else {
            "Relay error: $code"
        }

        // Errors before welcome mean joining did not succeed.
        if (!welcomed) {
            ws.close(1000, null)
        }
    }

    /** Wipe message content before deleting its row. */
    private suspend fun removeNow(id: String) {
        if (id.isEmpty()) return

        db.withTransaction {
            dao.wipe(id)
            dao.deleteById(id)
        }
    }

    // ------------------------------------------------------------
    // Initialization
    // ------------------------------------------------------------

    init {
        scope.launch {
            sweeperLoop()
        }

        scope.launch {
            connectionLoop()
        }
    }
}
