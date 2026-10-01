// SPDX-License-Identifier: MIT
package dev.morpbox.app.crypto

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.util.Base64
import kotlin.math.floor
import kotlin.math.ln

/**
 * NIP-44 v2 (payload version 0x02). Conversation key is HKDF-Extract over secp256k1 ECDH.
 * Used for kind-13 seals and kind-1059 gift wraps.
 */
object Nip44 {
    private val SALT = "nip44-v2".toByteArray(Charsets.US_ASCII)

    fun conversationKey(sk: ByteArray, peerXOnly: ByteArray): ByteArray {
        val shared = Secp256k1.ecdhX(sk, peerXOnly)
        // HKDF-Extract(salt = "nip44-v2", ikm = shared_x) == HMAC-SHA256(salt, ikm)
        return Secp256k1.hmacSha256(SALT, shared)
    }

    fun encrypt(conversationKey: ByteArray, plaintext: String, nonce: ByteArray = randomBytes(32)): String {
        require(conversationKey.size == 32 && nonce.size == 32)
        val padded = pad(plaintext.toByteArray(Charsets.UTF_8))
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val ciphertext = chacha20(chachaKey, chachaNonce, padded)
        val mac = Secp256k1.hmacSha256(hmacKey, nonce + ciphertext)
        return Base64.getEncoder().encodeToString(byteArrayOf(0x02) + nonce + ciphertext + mac)
    }

    fun decrypt(conversationKey: ByteArray, payloadB64: String): String {
        val raw = try {
            Base64.getDecoder().decode(payloadB64)
        } catch (_: Exception) {
            // Some clients omit padding.
            Base64.getDecoder().decode(payloadB64.replace(" ", ""))
        }
        require(raw.size >= 1 + 32 + 32 + 1) { "nip44 payload too short" }
        require(raw[0] == 0x02.toByte()) { "unsupported nip44 version ${raw[0]}" }
        val nonce = raw.copyOfRange(1, 33)
        val mac = raw.copyOfRange(raw.size - 32, raw.size)
        val ciphertext = raw.copyOfRange(33, raw.size - 32)
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val expect = Secp256k1.hmacSha256(hmacKey, nonce + ciphertext)
        require(mac.contentEquals(expect)) { "nip44 mac mismatch" }
        val padded = chacha20(chachaKey, chachaNonce, ciphertext)
        return String(unpad(padded), Charsets.UTF_8)
    }

    private fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val hkdf = HKDFBytesGenerator(org.bouncycastle.crypto.digests.SHA256Digest())
        hkdf.init(HKDFParameters.skipExtractParameters(conversationKey, nonce))
        val keys = ByteArray(76)
        hkdf.generateBytes(keys, 0, 76)
        return Triple(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), keys.copyOfRange(44, 76))
    }

    private fun chacha20(key: ByteArray, nonce12: ByteArray, data: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce12))
        val out = ByteArray(data.size)
        engine.processBytes(data, 0, data.size, out, 0)
        return out
    }

    internal fun calcPaddedLen(unpadded: Int): Int {
        require(unpadded in 1..65535) { "nip44 length $unpadded" }
        if (unpadded <= 32) return 32
        val nextPower = 1 shl (floor(ln((unpadded - 1).toDouble()) / ln(2.0)).toInt() + 1)
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpadded - 1) / chunk + 1)
    }

    private fun pad(unpadded: ByteArray): ByteArray {
        val paddedLen = calcPaddedLen(unpadded.size)
        val out = ByteArray(2 + paddedLen)
        out[0] = ((unpadded.size ushr 8) and 0xFF).toByte()
        out[1] = (unpadded.size and 0xFF).toByte()
        System.arraycopy(unpadded, 0, out, 2, unpadded.size)
        return out
    }

    private fun unpad(padded: ByteArray): ByteArray {
        require(padded.size >= 3)
        val len = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        require(len in 1..padded.size - 2) { "nip44 padding" }
        require(padded.size - 2 == calcPaddedLen(len)) { "nip44 padded length" }
        for (i in 2 + len until padded.size) require(padded[i] == 0.toByte()) { "nip44 trailing" }
        return padded.copyOfRange(2, 2 + len)
    }
}
