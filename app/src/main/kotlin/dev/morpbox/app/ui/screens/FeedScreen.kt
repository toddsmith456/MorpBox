// SPDX-License-Identifier: MIT
package dev.morpbox.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.morpbox.app.MorpSession
import dev.morpbox.app.protocol.NostrEvent
import dev.morpbox.app.ui.MorpIcons
import dev.youniversal.theme.YouniversalAssistChip
import dev.youniversal.theme.YouniversalButton
import dev.youniversal.theme.YouniversalCard
import dev.youniversal.theme.YouniversalEmptyState
import dev.youniversal.theme.YouniversalFilterChip
import dev.youniversal.theme.YouniversalMetrics
import dev.youniversal.theme.YouniversalSettingRow
import dev.youniversal.theme.YouniversalTextButton
import dev.youniversal.theme.YouniversalTextField
import kotlinx.coroutines.launch

@Composable
fun FeedScreen(
    session: MorpSession,
    modifier: Modifier = Modifier,
    onOpen: (NostrEvent) -> Unit,
) {
    val feed by session.feed.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        YouniversalCard(Modifier.padding(YouniversalMetrics.ScreenPadding)) {
            Column(
                Modifier.padding(YouniversalMetrics.SpacingLg),
                verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingSm),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingSm)) {
                    YouniversalAssistChip(text = status.internet, onClick = {})
                    YouniversalAssistChip(text = "${status.blePeers} nearby", onClick = {})
                    YouniversalAssistChip(text = status.policy, onClick = {})
                }
                YouniversalTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Note",
                    placeholder = "Works offline. Mesh and internet cover for each other.",
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingSm)) {
                    listOf("auto", "force-mesh", "force-internet").forEach { p ->
                        YouniversalFilterChip(
                            text = p.removePrefix("force-"),
                            selected = status.policy == p,
                            onClick = { session.setPolicy(p) },
                        )
                    }
                }
                YouniversalButton(
                    text = "Sign & send",
                    onClick = {
                        sending = true
                        scope.launch {
                            val r = session.post(draft.trim())
                            sending = false
                            r.onSuccess {
                                draft = ""
                                session.notify("Queued · ${status.policy}")
                            }.onFailure { session.notify(it.message ?: "failed") }
                        }
                    },
                    enabled = draft.isNotBlank() && !sending,
                    loading = sending,
                    leadingIcon = MorpIcons.Send,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (feed.isEmpty()) {
            YouniversalEmptyState(
                title = "No notes yet",
                description = "Turn mesh on to gossip with phones nearby, or wait for a relay. Posts you write are signed once and retry every path.",
                icon = MorpIcons.Feed,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = YouniversalMetrics.ScreenPadding, vertical = YouniversalMetrics.SpacingSm),
                verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingSm),
            ) {
                items(feed, key = { it.event.id }) { item ->
                    NoteCard(session, item.event, item.authorName, item.via, onOpen)
                }
            }
        }
    }
}

@Composable
fun NoteCard(
    session: MorpSession,
    event: NostrEvent,
    author: String,
    via: String,
    onOpen: (NostrEvent) -> Unit,
) {
    val scope = rememberCoroutineScope()
    YouniversalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(YouniversalMetrics.SpacingLg), verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingXs)) {
            YouniversalSettingRow(
                title = author,
                subtitle = "${event.pubkey.take(8)} · via $via",
                onClick = { onOpen(event) },
            )
            Text(event.content, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = YouniversalMetrics.SpacingSm))
            Row(horizontalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingSm)) {
                YouniversalTextButton("Reply", onClick = { onOpen(event) })
                YouniversalTextButton("+", onClick = { scope.launch { session.react(event) } })
                YouniversalTextButton("Zap", onClick = {
                    scope.launch {
                        val r = session.zap(event, 21)
                        session.notify(r.message.ifBlank { if (r.ok) r.invoice else "zap failed" })
                    }
                })
            }
        }
    }
}
