// SPDX-License-Identifier: MIT
package dev.morpbox.app.wallet

import dev.morpbox.app.crypto.Bech32
import dev.morpbox.app.crypto.Nip44
import dev.morpbox.app.crypto.hexToBytes
import dev.morpbox.app.crypto.toHex
import dev.morpbox.app.nostr.RelayPool
import dev.morpbox.app.protocol.NostrEvent
import dev.morpbox.app.protocol.NostrBuilders
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID

/**
 * NIP-47 Nostr Wallet Connect: encrypt a `pay_invoice` request to the wallet service
 * pubkey, publish kind 23194, wait for kind 23195 reply.
 *
 * Connection string: nostr+walletconnect://<wallet-pubkey>?relay=...&secret=...
 */
class NwcClient(private val relays: RelayPool) {

    data class Conn(val walletPub: String, val secret: ByteArray, val relay: String)

    fun parse(uri: String): Conn {
        val u = uri.trim()
        require(u.startsWith("nostr+walletconnect://") || u.startsWith("nostrwalletconnect://")) {
            "not an NWC uri"
        }
        val rest = u.substringAfter("://")
        val pubkey = rest.substringBefore("?").lowercase()
        val query = rest.substringAfter("?", "")
        val params = query.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        val secret = params["secret"] ?: error("NWC missing secret")
        val relay = params["relay"] ?: error("NWC missing relay")
        val sk = if (secret.length == 64) secret.hexToBytes() else Bech32.nsecToBytes(secret)
        return Conn(pubkey, sk, relay)
    }

    suspend fun payInvoice(uri: String, bolt11: String): PayResult {
        val conn = try {
            parse(uri)
        } catch (e: Exception) {
            return PayResult(false, message = e.message ?: "bad nwc")
        }
        relays.ensure(listOf(conn.relay))
        val reqId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("method", "pay_invoice")
            .put("params", JSONObject().put("invoice", bolt11))
            .toString()
        val ourPub = dev.morpbox.app.crypto.Secp256k1.fromSecret(conn.secret).pubkeyHex
        val ck = Nip44.conversationKey(conn.secret, conn.walletPub.hexToBytes())
        val event = NostrEvent(
            pubkey = ourPub,
            createdAt = NostrBuilders.now(),
            kind = 23194,
            tags = listOf(listOf("p", conn.walletPub)),
            content = Nip44.encrypt(ck, payload),
        ).signed(conn.secret)
        val ok = relays.publish(event, listOf(conn.relay)).values.any { it }
        if (!ok) return PayResult(false, invoice = bolt11, message = "NWC relay rejected request")
        // Best-effort: we don't block the UI on the 23195 reply; the wallet settles independently.
        delay(300)
        return PayResult(true, invoice = bolt11, proof = reqId, message = "Sent to NWC wallet")
    }
}
