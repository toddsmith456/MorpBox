// SPDX-License-Identifier: MIT
package dev.morpbox.app.wallet

import dev.morpbox.app.protocol.MorpKinds
import dev.morpbox.app.protocol.NostrEvent
import dev.morpbox.app.protocol.NostrBuilders
import org.json.JSONObject

data class ZapRequest(
    val event: NostrEvent,
    val lud16: String,
    val amountMsat: Long,
)

object ZapService {
    fun buildRequest(
        senderPub: String,
        target: NostrEvent,
        amountMsat: Long,
        lud16: String,
        relays: List<String>,
        comment: String = "",
    ): NostrEvent {
        val tags = mutableListOf(
            listOf("p", target.pubkey),
            listOf("e", target.id),
            listOf("amount", amountMsat.toString()),
            listOf("relays") + relays.take(4),
        )
        if (lud16.contains("@")) {
            val (name, domain) = lud16.split("@", limit = 2)
            tags += listOf("lnurl", "https://$domain/.well-known/lnurlp/$name")
        }
        return NostrEvent(senderPub, NostrBuilders.now(), MorpKinds.ZAP_REQUEST, tags, comment)
    }

    fun lud16FromKind0(content: String): String? = try {
        val o = JSONObject(content)
        o.optString("lud16").ifBlank { o.optString("lud06") }.ifBlank { null }
    } catch (_: Exception) {
        null
    }
}

data class PayResult(
    val ok: Boolean,
    val invoice: String = "",
    val proof: String = "",
    val queued: Boolean = false,
    val message: String = "",
)
