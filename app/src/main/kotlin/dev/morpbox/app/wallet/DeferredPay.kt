// SPDX-License-Identifier: MIT
package dev.morpbox.app.wallet

import dev.morpbox.app.crypto.Secp256k1
import dev.morpbox.app.crypto.hexToBytes
import dev.morpbox.app.crypto.randomBytes
import dev.morpbox.app.crypto.sha256
import dev.morpbox.app.crypto.toHex
import dev.morpbox.app.protocol.MorpKinds
import dev.morpbox.app.protocol.NostrEvent
import org.json.JSONObject

/**
 * Signed, non-revocable payment instruction. The payer's own device executes it
 * the next time it has internet (portal never holds keys). 72 h expiry.
 */
data class PaymentInstruction(
    val asset: String, // ln | xmr
    val payto: String,
    val amountMsat: Long? = null,
    val amountAtomic: Long? = null,
    val nonce: String = randomBytes(8).toHex(),
    val created: Long = System.currentTimeMillis() / 1000,
    val expires: Long = 0,
    val memo: String = "",
    val payerNostr: String = "",
    val sigHex: String = "",
) {
    companion object {
        const val TTL_S = 72 * 3600L
    }

    private fun expiry(): Long = if (expires == 0L) created + TTL_S else expires

    fun canonical(): ByteArray = JSONObject()
        .put("v", 1).put("asset", asset).put("payto", payto)
        .put("msat", amountMsat ?: JSONObject.NULL)
        .put("atomic", amountAtomic ?: JSONObject.NULL)
        .put("nonce", nonce).put("created", created).put("expires", expiry())
        .put("memo", memo).put("payer", payerNostr).toString().toByteArray()

    val expired: Boolean get() = expiry() < System.currentTimeMillis() / 1000

    fun signed(sk: ByteArray, payer: String): PaymentInstruction {
        val digest = sha256(canonical())
        val sig = Secp256k1.sign(sk, digest).toHex()
        return copy(payerNostr = payer, sigHex = sig)
    }

    fun verify(): Boolean {
        if (sigHex.isEmpty() || payerNostr.isEmpty() || expired) return false
        return try {
            Secp256k1.verify(payerNostr.hexToBytes(), sha256(canonical()), sigHex.hexToBytes())
        } catch (_: Exception) {
            false
        }
    }

    fun toUnsignedEvent(): NostrEvent = NostrEvent(
        pubkey = payerNostr,
        createdAt = created,
        kind = MorpKinds.MORP_PAYMENT,
        tags = listOf(
            listOf("asset", asset),
            listOf("payto", payto),
            listOf("nonce", nonce),
            listOf("expiration", expiry().toString()),
        ),
        content = canonical().toString(Charsets.UTF_8),
    )
}
