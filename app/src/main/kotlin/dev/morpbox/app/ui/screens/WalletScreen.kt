// SPDX-License-Identifier: MIT
package dev.morpbox.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.morpbox.app.MorpSession
import dev.morpbox.app.ui.MorpIcons
import dev.youniversal.theme.YouniversalButton
import dev.youniversal.theme.YouniversalCard
import dev.youniversal.theme.YouniversalMetrics
import dev.youniversal.theme.YouniversalOutlinedButton
import dev.youniversal.theme.YouniversalOutlinedTextField
import dev.youniversal.theme.YouniversalSectionHeader
import dev.youniversal.theme.YouniversalSettingRow
import kotlinx.coroutines.launch

@Composable
fun WalletScreen(session: MorpSession, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val outbox by session.outbox.collectAsStateWithLifecycle()
    var nwc by remember { mutableStateOf(session.identity.nwcUri) }
    var lud16 by remember { mutableStateOf(session.identity.lud16) }
    var xmr by remember { mutableStateOf(session.identity.xmrAddress) }
    var xmrPayto by remember { mutableStateOf("") }
    var xmrAmt by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(YouniversalMetrics.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd),
    ) {
        YouniversalCard {
            Column(Modifier.padding(YouniversalMetrics.SpacingLg), verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd)) {
                YouniversalSectionHeader(
                    title = "Zaps & xaps",
                    subtitle = "Lightning zaps (NIP-57) and Monero payto (kind 10133). Offline payments queue until a path exists.",
                )
                YouniversalOutlinedTextField(
                    value = lud16,
                    onValueChange = { lud16 = it; session.identity.lud16 = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Lightning address (lud16)",
                    placeholder = "you@getalby.com",
                )
                YouniversalOutlinedTextField(
                    value = nwc,
                    onValueChange = { nwc = it; session.identity.nwcUri = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "NWC connection",
                    placeholder = "nostr+walletconnect://…",
                )
                YouniversalOutlinedTextField(
                    value = xmr,
                    onValueChange = { xmr = it; session.identity.xmrAddress = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "XMR subaddress",
                    placeholder = "4… or 8…",
                )
                YouniversalOutlinedButton(
                    text = "Publish addresses on Nostr",
                    onClick = { scope.launch { session.publishProfile(); session.notify("Profile queued") } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        YouniversalCard {
            Column(Modifier.padding(YouniversalMetrics.SpacingLg), verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd)) {
                YouniversalSectionHeader(title = "Send XMR (xap)", subtitle = "Signed instruction. Broadcasts when you next have internet, or via a P3 portal.")
                YouniversalOutlinedTextField(value = xmrPayto, onValueChange = { xmrPayto = it }, modifier = Modifier.fillMaxWidth(), label = "Pay to")
                YouniversalOutlinedTextField(value = xmrAmt, onValueChange = { xmrAmt = it }, modifier = Modifier.fillMaxWidth(), label = "Atomic units")
                YouniversalButton(
                    text = "Queue xap",
                    onClick = {
                        scope.launch {
                            val r = session.xap(xmrPayto, xmrAmt.toLongOrNull() ?: 0L, "xap")
                            session.notify(r.message)
                        }
                    },
                    enabled = xmrPayto.isNotBlank(),
                    leadingIcon = MorpIcons.Bolt,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        YouniversalCard {
            Column {
                YouniversalSectionHeader(
                    title = "Outbox",
                    subtitle = "Sign once, send many ways",
                )
                if (outbox.isEmpty()) {
                    YouniversalSettingRow("Empty", subtitle = "Notes, DMs and payments land here until a path confirms.")
                } else {
                    outbox.take(20).forEach { item ->
                        YouniversalSettingRow(
                            title = item.op,
                            subtitle = "${item.state} · ${item.id.take(12)}",
                            trailing = {
                                if (item.extra.startsWith("ln")) {
                                    YouniversalOutlinedButton("Copy invoice", onClick = {
                                        copy(ctx, item.extra)
                                        session.notify("Invoice copied")
                                    })
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("morpbox", text))
}
