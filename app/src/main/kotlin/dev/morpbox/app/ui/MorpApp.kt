// SPDX-License-Identifier: MIT
package dev.morpbox.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.morpbox.app.MorpSession
import dev.morpbox.app.mesh.BleMeshTransport
import dev.morpbox.app.protocol.NostrEvent
import dev.morpbox.app.ui.screens.ChatsScreen
import dev.morpbox.app.ui.screens.FeedScreen
import dev.morpbox.app.ui.screens.MeshScreen
import dev.morpbox.app.ui.screens.ChatThread
import dev.morpbox.app.ui.screens.ThreadScreen
import dev.morpbox.app.ui.screens.WalletScreen
import dev.morpbox.app.ui.screens.YouScreen
import dev.youniversal.theme.YouniversalBottomBar
import dev.youniversal.theme.YouniversalFilterChip
import dev.youniversal.theme.YouniversalIconButton
import dev.youniversal.theme.YouniversalNavigationItem
import dev.youniversal.theme.YouniversalScaffold
import dev.youniversal.theme.YouniversalSnackbarHost
import dev.youniversal.theme.YouniversalThemeState
import dev.youniversal.theme.YouniversalTopBar
import kotlinx.coroutines.flow.collectLatest

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun MorpApp(session: MorpSession, theme: YouniversalThemeState) {
    val tabs = remember {
        listOf(
            Tab("Feed", MorpIcons.Feed),
            Tab("Chats", MorpIcons.Chat),
            Tab("Mesh", MorpIcons.Mesh),
            Tab("Wallet", MorpIcons.Wallet),
            Tab("You", MorpIcons.You),
        )
    }
    var tab by remember { mutableIntStateOf(0) }
    var thread by remember { mutableStateOf<NostrEvent?>(null) }
    var chatRoom by remember { mutableStateOf<String?>(null) }
    val status by session.status.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) session.setMesh(true)
        else session.notify("Bluetooth permission is required for mesh")
    }

    LaunchedEffect(session) {
        session.toasts.collectLatest { snack.showSnackbar(it) }
    }

    YouniversalScaffold(
        topBar = {
            YouniversalTopBar(
                title = when {
                    thread != null -> "Note"
                    chatRoom != null -> "Chat"
                    else -> tabs[tab].label
                },
                navigationIcon = {
                    if (thread != null || chatRoom != null) {
                        YouniversalIconButton(MorpIcons.Close, "Back", onClick = {
                            thread = null
                            chatRoom = null
                        })
                    }
                },
                actions = {
                    YouniversalFilterChip(
                        text = if (status.meshOn) "MESH ON" else "MESH",
                        selected = status.meshOn,
                        onClick = {
                            if (status.meshOn) session.setMesh(false)
                            else {
                                val perms = buildList {
                                    addAll(BleMeshTransport.requiredPermissions())
                                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                permLauncher.launch(perms.toTypedArray())
                            }
                        },
                    )
                },
            )
        },
        bottomBar = {
            if (thread == null && chatRoom == null) {
                YouniversalBottomBar {
                    tabs.forEachIndexed { i, t ->
                        YouniversalNavigationItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = t.icon,
                            label = t.label,
                        )
                    }
                }
            }
        },
        snackbarHost = { YouniversalSnackbarHost(snack) },
    ) { padding ->
        val mod = Modifier.padding(padding)
        when {
            thread != null -> ThreadScreen(session, thread!!, mod) { thread = null }
            chatRoom != null -> ChatThread(session, chatRoom!!, mod)
            else -> when (tab) {
                0 -> FeedScreen(session, mod, onOpen = { thread = it })
                1 -> ChatsScreen(session, mod, onOpen = { chatRoom = it })
                2 -> MeshScreen(session, mod)
                3 -> WalletScreen(session, mod)
                4 -> YouScreen(session, theme, mod)
            }
        }
    }
}
