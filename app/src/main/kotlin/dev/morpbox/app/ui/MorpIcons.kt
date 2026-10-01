// SPDX-License-Identifier: MIT
package dev.morpbox.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object MorpIcons {
    private const val V = 24f
    private val Ink = SolidColor(Color.Black)

    private fun vector(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, V.dp, V.dp, V, V).apply(block).build()

    val Feed: ImageVector by lazy {
        vector("Feed") {
            path(
                stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round, fill = null,
            ) {
                moveTo(4f, 6f); lineTo(20f, 6f)
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(4f, 18f); lineTo(14f, 18f)
            }
        }
    }

    val Chat: ImageVector by lazy {
        vector("Chat") {
            path(
                stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round, fill = null,
            ) {
                moveTo(5f, 6f); lineTo(19f, 6f); quadTo(21f, 6f, 21f, 8f)
                lineTo(21f, 15f); quadTo(21f, 17f, 19f, 17f); lineTo(10f, 17f)
                lineTo(5f, 21f); lineTo(5f, 17f); quadTo(3f, 17f, 3f, 15f)
                lineTo(3f, 8f); quadTo(3f, 6f, 5f, 6f); close()
            }
        }
    }

    val Mesh: ImageVector by lazy {
        vector("Mesh") {
            path(
                stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round, fill = null,
            ) {
                moveTo(12f, 4f); lineTo(20f, 9f); lineTo(20f, 15f); lineTo(12f, 20f)
                lineTo(4f, 15f); lineTo(4f, 9f); close()
                moveTo(12f, 4f); lineTo(12f, 20f)
                moveTo(4f, 9f); lineTo(20f, 15f)
                moveTo(20f, 9f); lineTo(4f, 15f)
            }
        }
    }

    val Wallet: ImageVector by lazy {
        vector("Wallet") {
            path(
                stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round, fill = null,
            ) {
                moveTo(3f, 8f); lineTo(21f, 8f); lineTo(21f, 19f); lineTo(3f, 19f); close()
                moveTo(3f, 8f); lineTo(3f, 6f); lineTo(18f, 6f)
                moveTo(16f, 13.5f); lineTo(19f, 13.5f)
            }
        }
    }

    val You: ImageVector by lazy {
        vector("You") {
            path(
                stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
                fill = null,
            ) {
                moveTo(12f, 7f)
                arcTo(3f, 3f, 0f, false, true, 12f, 13f)
                arcTo(3f, 3f, 0f, false, true, 12f, 7f)
                close()
                moveTo(6f, 20f)
                quadTo(6f, 16f, 12f, 16f)
                quadTo(18f, 16f, 18f, 20f)
            }
        }
    }

    val Send: ImageVector by lazy {
        vector("Send") {
            path(fill = Ink) {
                moveTo(3f, 11f); lineTo(21f, 3f); lineTo(14f, 21f); lineTo(11f, 13f); close()
            }
        }
    }

    val Bolt: ImageVector by lazy {
        vector("Bolt") {
            path(fill = Ink) {
                moveTo(13f, 2f); lineTo(4f, 14f); lineTo(11f, 14f); lineTo(11f, 22f)
                lineTo(20f, 10f); lineTo(13f, 10f); close()
            }
        }
    }

    val Plus: ImageVector by lazy {
        vector("Plus") {
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, fill = null) {
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(5f, 12f); lineTo(19f, 12f)
            }
        }
    }

    val Shield: ImageVector by lazy {
        vector("Shield") {
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); lineTo(20f, 6f); lineTo(20f, 12f)
                quadTo(20f, 18f, 12f, 21f); quadTo(4f, 18f, 4f, 12f)
                lineTo(4f, 6f); close()
            }
        }
    }

    val Copy: ImageVector by lazy {
        vector("Copy") {
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(8f, 8f); lineTo(18f, 8f); lineTo(18f, 20f); lineTo(8f, 20f); close()
                moveTo(6f, 16f); lineTo(6f, 4f); lineTo(16f, 4f)
            }
        }
    }

    val Reply: ImageVector by lazy {
        vector("Reply") {
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, fill = null) {
                moveTo(10f, 8f); lineTo(4f, 12f); lineTo(10f, 16f)
                moveTo(4f, 12f); lineTo(20f, 12f); quadTo(20f, 18f, 14f, 18f)
            }
        }
    }

    val Heart: ImageVector by lazy {
        vector("Heart") {
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 19f)
                lineTo(5f, 12f)
                quadTo(3f, 9f, 6f, 7f)
                quadTo(9f, 5f, 12f, 8f)
                quadTo(15f, 5f, 18f, 7f)
                quadTo(21f, 9f, 19f, 12f)
                close()
            }
        }
    }

    val Close: ImageVector by lazy {
        vector("Close") {
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, fill = null) {
                moveTo(6f, 6f); lineTo(18f, 18f)
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }
    }

    val Radio: ImageVector by lazy {
        vector("Radio") {
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, fill = null) {
                moveTo(5f, 12f); arcTo(7f, 7f, 0f, false, true, 19f, 12f)
                moveTo(8f, 12f); arcTo(4f, 4f, 0f, false, true, 16f, 12f)
                moveTo(12f, 12f); arcTo(1f, 1f, 0f, false, true, 12f, 12.1f)
            }
        }
    }
}
