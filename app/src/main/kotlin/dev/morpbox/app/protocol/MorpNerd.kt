// SPDX-License-Identifier: MIT
package dev.morpbox.app.protocol

import dev.morpbox.app.crypto.sha256
import java.security.MessageDigest

/** MORP NERD v0.1 constants — byte-identical to protocol/morp/{onion,frame}.py. */
object MorpNerd {
    const val VERSION: Byte = 0x01
    const val MIN_HOPS = 2
    const val MAX_HOPS = 5
    const val DEFAULT_HOPS = 3
    val PAD_TIERS = intArrayOf(512, 2048, 8192)
    const val MTU_BLE_MESHCORE = 180
    const val MTU_RNS = 480
    val NEXT_NONE = ByteArray(8) { 0 }
    const val HOP_DOMAIN = "morp-hop-v1"
    const val BOX_DOMAIN = "morp-nerd-hop-v1"

    fun hopId(devPub: ByteArray): ByteArray {
        require(devPub.size == 32)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(HOP_DOMAIN.toByteArray(Charsets.US_ASCII))
        md.update(devPub)
        return md.digest().copyOf(8)
    }

    fun msgId(packet: ByteArray): ByteArray = sha256(packet).copyOf(16)

    fun padTierFor(innerLenPlus4: Int): Int =
        PAD_TIERS.firstOrNull { innerLenPlus4 <= it }
            ?: throw IllegalArgumentException("payload exceeds max tier ${PAD_TIERS.last()}")
}
