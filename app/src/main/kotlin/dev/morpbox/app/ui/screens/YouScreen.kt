// SPDX-License-Identifier: MIT
package dev.morpbox.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.morpbox.app.MorpSession
import dev.morpbox.app.secure.SecureWipe
import dev.youniversal.theme.YouniversalBackgroundStyle
import dev.youniversal.theme.YouniversalCard
import dev.youniversal.theme.YouniversalContrast
import dev.youniversal.theme.YouniversalDivider
import dev.youniversal.theme.YouniversalMetrics
import dev.youniversal.theme.YouniversalOutlinedButton
import dev.youniversal.theme.YouniversalOutlinedTextField
import dev.youniversal.theme.YouniversalProgressBar
import dev.youniversal.theme.YouniversalSectionHeader
import dev.youniversal.theme.YouniversalSegmentedControl
import dev.youniversal.theme.YouniversalSettingRow
import dev.youniversal.theme.YouniversalSlider
import dev.youniversal.theme.YouniversalSwitch
import dev.youniversal.theme.YouniversalThemeState

@Composable
fun YouScreen(session: MorpSession, theme: YouniversalThemeState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(session.identity.displayName) }
    var about by remember { mutableStateOf(session.identity.about) }
    var relays by remember { mutableStateOf(session.identity.relays.joinToString("\n")) }
    var importKey by remember { mutableStateOf("") }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(YouniversalMetrics.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd),
    ) {
        YouniversalCard {
            Column(Modifier.padding(YouniversalMetrics.SpacingLg), verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd)) {
                YouniversalSectionHeader(title = "Identity", subtitle = "Generated on first launch. Amber-compatible npub.")
                YouniversalSettingRow("npub", subtitle = session.npub, trailing = {
                    YouniversalOutlinedButton("Copy", onClick = { copy(ctx, session.npub) })
                })
                YouniversalOutlinedTextField(value = name, onValueChange = { name = it; session.identity.displayName = it }, modifier = Modifier.fillMaxWidth(), label = "Display name")
                YouniversalOutlinedTextField(value = about, onValueChange = { about = it; session.identity.about = it }, modifier = Modifier.fillMaxWidth(), label = "About")
                YouniversalOutlinedTextField(
                    value = relays,
                    onValueChange = {
                        relays = it
                        session.identity.relays = it.lines().map { l -> l.trim() }.filter { l -> l.startsWith("wss://") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Relays (one per line)",
                    singleLine = false,
                )
                YouniversalOutlinedButton("Copy nsec (secret)", onClick = { copy(ctx, session.identity.exportNsec()) }, modifier = Modifier.fillMaxWidth())
                YouniversalOutlinedTextField(value = importKey, onValueChange = { importKey = it }, modifier = Modifier.fillMaxWidth(), label = "Import nsec", placeholder = "nsec1…")
                YouniversalOutlinedButton(
                    text = "Import (restart after)",
                    onClick = {
                        runCatching { session.identity.importNsec(importKey.trim()) }
                            .onSuccess { session.notify("Imported. Force-stop the app to reload keys.") }
                            .onFailure { session.notify(it.message ?: "import failed") }
                    },
                    enabled = importKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ThemeCard(theme)

        WipeCard(ctx)
    }
}

@Composable
private fun ThemeCard(state: YouniversalThemeState) {
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val seeds = listOf(
        "Brand" to Color.Unspecified,
        "Teal" to Color(0xFF0F9C8E),
        "Rose" to Color(0xFFC2185B),
        "Forest" to Color(0xFF1B7F3B),
        "Amber" to Color(0xFFB26A00),
        "Violet" to Color(0xFF6A3FB5),
    )
    YouniversalCard {
        Column(Modifier.padding(YouniversalMetrics.SpacingLg), verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd)) {
            YouniversalSectionHeader(
                title = "Youniversal theme",
                subtitle = "Material You, Light / Dark / Cream, contrast, seeds. Live and persisted.",
                action = {
                    Text(
                        "Reset",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { state.reset() },
                    )
                },
            )
            YouniversalSettingRow(
                title = "Youniversal",
                subtitle = if (state.enabled) "Applied to the whole app" else "Off — baseline Material 3",
                trailing = { YouniversalSwitch(checked = state.enabled, onCheckedChange = state::setEnabled) },
            )
            Text("Background", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            YouniversalSegmentedControl(
                options = YouniversalBackgroundStyle.entries.toList(),
                selected = state.backgroundStyle,
                onOptionSelected = state::setBackgroundStyle,
                modifier = Modifier.fillMaxWidth(),
            ) { option, _ -> Text(option.label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }

            Text("Contrast", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            YouniversalSegmentedControl(
                options = YouniversalContrast.entries.toList(),
                selected = state.contrast,
                onOptionSelected = state::setContrast,
                modifier = Modifier.fillMaxWidth(),
            ) { option, _ -> Text(option.label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }

            YouniversalSettingRow(
                title = "Material You",
                subtitle = if (!dynamicAvailable) "Requires Android 12+" else "Palette from the wallpaper",
                enabled = dynamicAvailable,
                trailing = {
                    YouniversalSwitch(
                        checked = state.dynamicColor,
                        onCheckedChange = state::setDynamicColor,
                        enabled = dynamicAvailable,
                    )
                },
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                seeds.forEach { (name, color) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(if (color == Color.Unspecified) MaterialTheme.colorScheme.surfaceContainerHighest else color)
                                .border(
                                    width = if (state.accentSeed == color) 2.dp else 1.dp,
                                    color = if (state.accentSeed == color) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .clickable { state.setAccentSeed(color) },
                        )
                        Text(name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            YouniversalDivider()
            YouniversalSettingRow("Corner radius", subtitle = "%.0f%%".format(state.cornerScale * 100f))
            YouniversalSlider(value = state.cornerScale, onValueChange = state::setCornerScale, valueRange = 0f..2f)
            YouniversalSettingRow("Text size", subtitle = "%.0f%%".format(state.fontScale * 100f))
            YouniversalSlider(value = state.fontScale, onValueChange = state::setFontScale, valueRange = 0.8f..1.6f)
            YouniversalSettingRow(
                title = "Animate theme changes",
                trailing = { YouniversalSwitch(checked = state.animateTransitions, onCheckedChange = state::setAnimateTransitions) },
            )
        }
    }
}

@Composable
private fun WipeCard(ctx: Context) {
    var taps by remember { mutableStateOf(listOf<Long>()) }
    var armed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    YouniversalCard {
        Column(Modifier.padding(YouniversalMetrics.SpacingLg), verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd)) {
            YouniversalSectionHeader(
                title = "Emergency wipe",
                subtitle = "Triple-tap, hold 3 seconds. Crypto-shreds keys and local cache.",
            )
            Box(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(if (armed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable {
                        val now = SystemClock.elapsedRealtime()
                        val window = (taps + now).filter { now - it < 800 }
                        taps = window
                        if (window.size >= 3) armed = true
                    }
                    .padding(YouniversalMetrics.SpacingLg),
            ) {
                Text(if (armed) "ARMED — hold below for 3 seconds" else "Triple-tap to arm")
            }
            if (armed) HoldToWipe {
                result = SecureWipe.wipeAll(ctx)
                armed = false
            }
            result?.let { Text("wiped: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun HoldToWipe(onDone: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                val start = SystemClock.elapsedRealtime()
                var released = false
                while (!released && progress < 1f) {
                    val ev = awaitPointerEvent(PointerEventPass.Main)
                    released = ev.changes.all { !it.pressed }
                    progress = ((SystemClock.elapsedRealtime() - start) / 3000f).coerceIn(0f, 1f)
                    if (progress >= 1f) onDone()
                }
                if (released) progress = 0f
            }
        },
    ) {
        YouniversalProgressBar(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text("HOLD 3s TO WIPE", style = MaterialTheme.typography.labelLarge)
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("morpbox", text))
}
