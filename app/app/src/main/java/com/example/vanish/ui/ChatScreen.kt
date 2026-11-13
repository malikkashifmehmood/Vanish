package com.example.vanish.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.vanish.data.ChatRepository
import com.example.vanish.data.Connection
import com.example.vanish.data.MessageEntity
import com.example.vanish.data.Protocol
import com.example.vanish.data.State
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(repo: ChatRepository) {
    val messages by repo.messages.collectAsState(initial = emptyList())
    val connection by repo.connection.collectAsState()
    val stored by repo.storedCount.collectAsState(initial = 0)
    val notice by repo.notice.collectAsState()
    val name by repo.name.collectAsState()

    // The only clock the UI uses: relay time. Every countdown is expires_at minus this value, so it
    // is correct after rotation, backgrounding or a wrong phone clock, because nothing is counted down.
    var now by remember { mutableLongStateOf(repo.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = repo.now()
            delay(200)
        }
    }

    var draft by rememberSaveable { mutableStateOf("") }
    val visible = messages.filter { it.expiresAt == 0L || it.expiresAt > now }

    // Lifecycle: a received message becomes DISPLAYED once it is on screen.
    val undisplayed = visible.filter { !it.mine && it.state == State.DELIVERED }.map { it.id }
    LaunchedEffect(undisplayed) { undisplayed.forEach { repo.markDisplayed(it) } }

    val listState = rememberLazyListState()
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        // ---- header ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(name ?: "", style = MaterialTheme.typography.titleMedium)
                val status = when (connection) {
                    Connection.ONLINE -> "Online"
                    Connection.CONNECTING -> "Connecting..."
                    Connection.OFFLINE -> "Offline (messages wait in the queue)"
                }
                Text(status, style = MaterialTheme.typography.labelMedium)
                val offsetSeconds = repo.clock.offsetMs() / 1000.0
                Text(
                    "relay clock ${"%+.1f".format(offsetSeconds)} s vs phone, stored rows: $stored",
                    style = MaterialTheme.typography.labelSmall
                )
                if (notice.isNotEmpty()) {
                    Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = { repo.leave() }) { Text("Leave") }
        }
        HorizontalDivider()

        // ---- messages ----
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(visible, key = { it.id }) { m -> Bubble(m, now) }
        }

        // ---- input ----
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(Protocol.MAX_TEXT) },
                placeholder = { Text("Message (gone in 30 s)") },
                maxLines = 3,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = draft.isNotBlank(),
                onClick = {
                    repo.send(draft)
                    draft = ""
                }
            ) { Text("Send") }
        }
    }
}

@Composable
private fun Bubble(m: MessageEntity, now: Long) {
    val remaining = if (m.expiresAt > 0L) (m.expiresAt - now).coerceAtLeast(0L) else -1L
    val dropped = m.state == State.DROPPED

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (m.mine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                if (!m.mine) {
                    Text(m.sender, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Text(if (dropped) "Not sent: it waited more than 30 s without a connection." else m.text)
                Spacer(Modifier.height(6.dp))
                if (remaining >= 0L && !dropped) {
                    LinearProgressIndicator(
                        progress = { (remaining.toFloat() / Protocol.TTL_MS).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${(remaining + 999) / 1000} s left, ${stateLabel(m)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    Text(stateLabel(m), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun stateLabel(m: MessageEntity): String = when {
    m.state == State.DROPPED -> "dropped"
    m.mine && m.state == State.WAITING -> "waiting for network"
    m.mine && m.state == State.SENT -> "sent"
    m.mine && m.state == State.DELIVERED -> "delivered"
    !m.mine && m.state == State.DISPLAYED -> "displayed"
    else -> "received"
}
