// SPDX-License-Identifier: MIT
package dev.morpbox.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.morpbox.app.MorpSession
import dev.morpbox.app.protocol.NostrEvent
import dev.morpbox.app.ui.MorpIcons
import dev.youniversal.theme.YouniversalButton
import dev.youniversal.theme.YouniversalCard
import dev.youniversal.theme.YouniversalIconButton
import dev.youniversal.theme.YouniversalMetrics
import dev.youniversal.theme.YouniversalOutlinedTextField
import dev.youniversal.theme.YouniversalTextButton
import kotlinx.coroutines.launch

@Composable
fun ThreadScreen(
    session: MorpSession,
    event: NostrEvent,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    var reply by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().padding(YouniversalMetrics.ScreenPadding), verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd)) {
        YouniversalCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(YouniversalMetrics.SpacingLg), verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingSm)) {
                Text(session.store.displayName(event.pubkey), style = MaterialTheme.typography.titleMedium)
                Text(event.content, style = MaterialTheme.typography.bodyLarge)
                Text(event.id.take(16), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingSm)) {
            YouniversalTextButton("Zap 21", onClick = {
                scope.launch { session.notify(session.zap(event, 21).message) }
            })
            YouniversalTextButton("Like", onClick = { scope.launch { session.react(event) } })
        }
        YouniversalOutlinedTextField(
            value = reply,
            onValueChange = { reply = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Reply",
            singleLine = false,
        )
        YouniversalButton(
            text = "Reply",
            onClick = {
                scope.launch {
                    session.post(reply, event)
                    reply = ""
                    onBack()
                }
            },
            enabled = reply.isNotBlank(),
            leadingIcon = MorpIcons.Reply,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ChatThread(session: MorpSession, roomId: String, modifier: Modifier = Modifier) {
    val history = remember(roomId) { session.history(roomId) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val peer = roomId.removePrefix("dm:")
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(YouniversalMetrics.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingSm),
        ) {
            items(history, key = { it.id }) { m ->
                val mine = m.outbound || m.sender == session.pubkey
                YouniversalCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(YouniversalMetrics.SpacingMd)) {
                        Text(if (mine) "you" else session.store.displayName(m.sender), style = MaterialTheme.typography.labelMedium)
                        Text(m.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        Row(
            Modifier.padding(YouniversalMetrics.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingSm),
        ) {
            YouniversalOutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                label = "Message",
                singleLine = true,
            )
            YouniversalIconButton(MorpIcons.Send, "Send", onClick = {
                val text = draft
                if (text.isBlank()) return@YouniversalIconButton
                draft = ""
                scope.launch {
                    if (roomId.startsWith("dm:")) session.sendDm(peer, text)
                    else session.sendGroup(emptyList(), text, roomId)
                }
            })
        }
    }
}
