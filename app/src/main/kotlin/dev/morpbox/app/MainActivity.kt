// SPDX-License-Identifier: MIT
package dev.morpbox.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.morpbox.app.ui.MorpApp
import dev.youniversal.theme.YouniversalTheme
import dev.youniversal.theme.rememberYouniversalThemeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val session = (application as MorpApplication).graph.session
        setContent {
            val theme = rememberYouniversalThemeState()
            YouniversalTheme(state = theme) {
                MorpApp(session = session, theme = theme)
            }
        }
    }
}
