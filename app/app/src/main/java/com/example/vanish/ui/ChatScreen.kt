
package com.example.vanish.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
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
    val notice by repo.notice.collectAsState()
    val name by repo.name.collectAsState()
    val onlineMembers by repo.onlineMembers.collectAsState()

    // Local immutable snapshot allows Kotlin to smart-cast safely.
    val memberList = onlineMembers

    // Use relay time for all message countdowns.
    var now by remember { mutableLongStateOf(repo.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = repo.now()
            delay(200)
        }
    }

    var draft by rememberSaveable { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var showConnectionDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showMembersDialog by remember { mutableStateOf(false) }

    val visible = messages.filter {
        it.expiresAt == 0L || it.expiresAt > now
    }

    // Mark received messages as displayed when they appear on screen.
    val undisplayed = visible
        .filter { !it.mine && it.state == State.DELIVERED }
        .map { it.id }

    LaunchedEffect(undisplayed) {
        undisplayed.forEach { repo.markDisplayed(it) }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.lastIndex)
        }
    }

    val connectionLabel = when (connection) {
        Connection.ONLINE -> "Online"
        Connection.CONNECTING -> "Connecting"
        Connection.OFFLINE -> "Offline"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 20.dp,
                    end = 8.dp,
                    top = 12.dp,
                    bottom = 8.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Vanish Chat",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = when (connection) {
                            Connection.ONLINE ->
                                MaterialTheme.colorScheme.primaryContainer

                            Connection.CONNECTING ->
                                MaterialTheme.colorScheme.tertiaryContainer

                            Connection.OFFLINE ->
                                MaterialTheme.colorScheme.errorContainer
                        }
                    ) {
                        Text(
                            text = connectionLabel,
                            modifier = Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 4.dp
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when (connection) {
                                Connection.ONLINE ->
                                    MaterialTheme.colorScheme.onPrimaryContainer

                                Connection.CONNECTING ->
                                    MaterialTheme.colorScheme.onTertiaryContainer

                                Connection.OFFLINE ->
                                    MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                    }

                    Text(
                        text = name.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Optional Presence information from the relay.
                TextButton(
                    onClick = { showMembersDialog = true },
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 0.dp,
                            vertical = 0.dp
                        )
                ) {
                    Text(
                        text = memberList?.let {
                            "Members (${it.size})"
                        } ?: "Waiting for member list…",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Text(
                        text = "⋮",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Connection details") },
                        onClick = {
                            menuExpanded = false
                            showConnectionDialog = true
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                "Leave chat",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            showLeaveDialog = true
                        }
                    )
                }
            }
        }

        HorizontalDivider()

        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    vertical = 12.dp
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (visible.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "No messages yet",
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text(
                                text = "Start a conversation. Messages disappear after 30 seconds.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(visible, key = { it.id }) { message ->
                Bubble(message, now)
            }
        }

        // Message composer
        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it.take(Protocol.MAX_TEXT)
                },
                placeholder = {
                    Text("Message...")
                },
                supportingText = {
                    Text("Disappears after 30 seconds")
                },
                maxLines = 3,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f)
            )

            Spacer(Modifier.width(8.dp))

            Button(
                enabled = draft.isNotBlank(),
                onClick = {
                    repo.send(draft)
                    draft = ""
                },
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Send")
            }
        }
    }

    // Online members dialog.
    if (showMembersDialog) {
        AlertDialog(
            onDismissRequest = { showMembersDialog = false },
            title = {
                Text(
                    text = memberList?.let {
                        "Online members (${it.size})"
                    } ?: "Online members"
                )
            },
            text = {
                val currentMembers = memberList

                when {
                    currentMembers == null -> {
                        Text(
                            "The relay has not supplied a member list yet. " +
                                "This feature requires the shared relay to send Presence updates."
                        )
                    }

                    currentMembers.isEmpty() -> {
                        Text("No members are currently online.")
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                        ) {
                            items(items = currentMembers) { member ->
                                Text(
                                    text = member,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 9.dp),
                                    style = MaterialTheme.typography.bodyLarge
                                )

                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showMembersDialog = false }
                ) {
                    Text("Close")
                }
            }
        )
    }

    // Connection information is available when requested.
    if (showConnectionDialog) {
        AlertDialog(
            onDismissRequest = { showConnectionDialog = false },
            title = {
                Text("Connection details")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Status: $connectionLabel")
                    Text("Display name: ${name.orEmpty()}")
                    Text("Relay: ${repo.settings.relayUrl}")

                    if (notice.isNotBlank()) {
                        Text(
                            text = notice,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showConnectionDialog = false }
                ) {
                    Text("Close")
                }
            }
        )
    }

    // Confirm before leaving because this action wipes local chat data.
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = {
                Text("Leave this chat?")
            },
            text = {
                Text(
                    "Your display name and locally stored messages will be cleared from this phone. You will need to join again to continue."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveDialog = false
                        repo.leave()
                    }
                ) {
                    Text(
                        "Leave",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLeaveDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun Bubble(m: MessageEntity, now: Long) {
    val remaining = if (m.expiresAt > 0L) {
        (m.expiresAt - now).coerceAtLeast(0L)
    } else {
        -1L
    }

    val dropped = m.state == State.DROPPED

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.mine) {
            Arrangement.End
        } else {
            Arrangement.Start
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            val bubbleWidth = minOf(maxWidth * 0.84f, 360.dp)

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (m.mine) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                modifier = Modifier.widthIn(max = bubbleWidth)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    if (!m.mine) {
                        Text(
                            text = m.sender,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(4.dp))
                    }

                    Text(
                        text = if (dropped) {
                            "Not sent: it waited more than 30 seconds without a connection."
                        } else {
                            m.text
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(Modifier.height(8.dp))

                    if (remaining >= 0L && !dropped) {
                        LinearProgressIndicator(
                            progress = {
                                (remaining.toFloat() / Protocol.TTL_MS)
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text = "${(remaining + 999) / 1000} s left · ${stateLabel(m)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = stateLabel(m),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun stateLabel(m: MessageEntity): String = when {
    m.state == State.DROPPED -> "Dropped"
    m.mine && m.state == State.WAITING -> "Waiting for network"
    m.mine && m.state == State.SENT -> "Sent"
    m.mine && m.state == State.DELIVERED -> "Delivered"
    !m.mine && m.state == State.DISPLAYED -> "Displayed"
    else -> "Received"
}
