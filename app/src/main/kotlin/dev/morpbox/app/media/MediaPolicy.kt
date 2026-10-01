// SPDX-License-Identifier: MIT
package dev.morpbox.app.media

object MediaPolicy {
    enum class Route { INTERNET, MESH, LORA }

    data class Cap(val maxBytes: Int, val maxDimOrSec: Int)

    private val LADDER: Map<String, Map<Route, Cap>> = mapOf(
        "photo" to mapOf(
            Route.INTERNET to Cap(2_000_000, 2048),
            Route.MESH to Cap(100_000, 1280),
            Route.LORA to Cap(30_000, 800),
        ),
        "audio" to mapOf(
            Route.INTERNET to Cap(3_600_000, 300),
            Route.MESH to Cap(90_000, 60),
            Route.LORA to Cap(30_000, 30),
        ),
        "video" to mapOf(
            Route.INTERNET to Cap(8_000_000, 60),
            Route.MESH to Cap(500_000, 15),
            Route.LORA to Cap(150_000, 6),
        ),
    )

    const val INLINE_THRESHOLD = 8 * 1024
    const val CHUNK_BYTES = 90

    data class Plan(val mediaType: String, val route: Route, val inline: Boolean)

    fun plan(mediaType: String, byteLen: Int, route: Route): Plan {
        val cap = LADDER[mediaType]?.get(route) ?: throw IllegalArgumentException("unknown media/route")
        require(byteLen <= cap.maxBytes) { "$mediaType ${byteLen}B exceeds ${route.name} cap ${cap.maxBytes}B" }
        return Plan(mediaType, route, byteLen <= INLINE_THRESHOLD)
    }
}
