// SPDX-License-Identifier: MIT
package dev.morpbox.app.protocol

import com.google.crypto.tink.subtle.ChaCha20Poly1305
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64

data class InnerRequest(
    val op: String,
    val payload: ByteArray,
    val relays: List<String> = emptyList(),
    val reqId: String = randomHex(8),
    val ts: Long = System.currentTimeMillis() / 1000,
    val reply: JSONObject? = null,
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("v", 1).put("req_id", reqId).put("ts", ts).put("op", op)
        .put("relays", JSONArray(relays))
        .put("payload", Base64.getEncoder().encodeToString(payload))
        .put("reply", reply ?: JSONObject.NULL).toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        fun fromPadded(padded: ByteArray): InnerRequest {
            val n = ByteBuffer.wrap(padded).int
            require(4 + n <= padded.size) { "bad padding length" }
            val o = JSONObject(String(padded, 4, n, Charsets.UTF_8))
            require(o.getInt("v") == 1)
            val relays = mutableListOf<String>()
            val ra = o.optJSONArray("relays")
            if (ra != null) for (i in 0 until ra.length()) relays += ra.getString(i)
            return InnerRequest(
                op = o.getString("op"),
                payload = Base64.getDecoder().decode(o.getString("payload")),
                relays = relays,
                reqId = o.getString("req_id"),
                ts = o.getLong("ts"),
                reply = if (o.isNull("reply")) null else o.getJSONObject("reply"),
            )
        }
    }
}

sealed interface PeelResult {
    data class Forward(val nextHop: ByteArray, val innerPacket: ByteArray, val remaining: Int) : PeelResult
    data class Exit(val innerPadded: ByteArray) : PeelResult
}

object OnionRouter {
    private val rng = SecureRandom()

    fun boxSeal(recipientPub: ByteArray, plaintext: ByteArray): ByteArray {
        require(recipientPub.size == 32)
        val ephPriv = X25519.generatePrivateKey()
        val ephPub = X25519.publicFromPrivate(ephPriv)
        val shared = X25519.computeSharedSecret(ephPriv, recipientPub)
        val key = Hkdf.computeHkdf("HmacSha256", shared, null, MorpNerd.BOX_DOMAIN.toByteArray(), 32)
        val ct = ChaCha20Poly1305(key).encrypt(plaintext, null)
        val nonce = ct.copyOf(12)
        return ephPub + nonce + ct.copyOfRange(12, ct.size)
    }

    fun boxOpen(recipientPriv: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size >= 32 + 12 + 16)
        val ephPub = blob.copyOfRange(0, 32)
        val nonce = blob.copyOfRange(32, 44)
        val ct = blob.copyOfRange(44, blob.size)
        val shared = X25519.computeSharedSecret(recipientPriv, ephPub)
        val key = Hkdf.computeHkdf("HmacSha256", shared, null, MorpNerd.BOX_DOMAIN.toByteArray(), 32)
        return ChaCha20Poly1305(key).decrypt(nonce + ct, null)
    }

    fun padInner(raw: ByteArray): ByteArray {
        val inner = ByteBuffer.allocate(4 + raw.size).putInt(raw.size).put(raw).array()
        val tier = MorpNerd.padTierFor(inner.size)
        return inner + ByteArray(tier - inner.size)
    }

    fun buildPacket(innerPadded: ByteArray, pathPubs: List<ByteArray>, pathHops: List<ByteArray>): ByteArray {
        require(pathPubs.size == pathHops.size && pathPubs.size in MorpNerd.MIN_HOPS..MorpNerd.MAX_HOPS)
        var blob = innerPadded
        var next = MorpNerd.NEXT_NONE
        for (i in pathPubs.indices.reversed()) {
            val plain = ByteBuffer.allocate(4 + blob.size + 8).putInt(blob.size).put(blob).put(next).array()
            blob = boxSeal(pathPubs[i], plain)
            next = pathHops[i]
        }
        return byteArrayOf(MorpNerd.VERSION, pathPubs.size.toByte()) + blob
    }

    fun peelPacket(packet: ByteArray, myPriv: ByteArray): PeelResult {
        require(packet.size >= 2 && packet[0] == MorpNerd.VERSION) { "bad packet version" }
        val remaining = packet[1].toInt()
        require(remaining >= 1) { "empty path" }
        val plain = boxOpen(myPriv, packet.copyOfRange(2, packet.size))
        val n = ByteBuffer.wrap(plain).int
        val inner = plain.copyOfRange(4, 4 + n)
        val nextHop = plain.copyOfRange(4 + n, 4 + n + 8)
        return if (nextHop.contentEquals(MorpNerd.NEXT_NONE)) PeelResult.Exit(inner)
        else PeelResult.Forward(
            nextHop,
            byteArrayOf(MorpNerd.VERSION, (remaining - 1).toByte()) + inner,
            remaining - 1,
        )
    }
}

internal fun randomHex(nBytes: Int): String {
    val b = ByteArray(nBytes).also { SecureRandom().nextBytes(it) }
    return b.joinToString("") { "%02x".format(it) }
}
