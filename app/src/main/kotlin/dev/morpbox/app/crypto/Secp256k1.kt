// SPDX-License-Identifier: MIT
package dev.morpbox.app.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom

/**
 * secp256k1 + BIP-340 Schnorr, plus x-coordinate ECDH for NIP-44.
 *
 * Pure BouncyCastle — no bitcoin-kmp / JNI — so the same code runs on device and on the JVM tests.
 */
object Secp256k1 {
    private val spec: ECNamedCurveParameterSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
    private val n: BigInteger = spec.n
    private val g: ECPoint = spec.g
    private val rng = SecureRandom()

    data class KeyPair(val secret: ByteArray, val pubkeyXOnly: ByteArray) {
        val pubkeyHex: String get() = pubkeyXOnly.toHex()
        val nsec: String get() = Bech32.bytesToNsec(secret)
        val npub: String get() = Bech32.hexToNpub(pubkeyHex)
    }

    fun generate(): KeyPair {
        val sk = ByteArray(32)
        while (true) {
            rng.nextBytes(sk)
            val d = BigInteger(1, sk)
            if (d > BigInteger.ZERO && d < n) break
        }
        return KeyPair(sk, xOnlyPub(sk))
    }

    fun fromSecret(sk: ByteArray): KeyPair {
        require(sk.size == 32)
        val d = BigInteger(1, sk)
        require(d > BigInteger.ZERO && d < n) { "secret out of range" }
        return KeyPair(sk.copyOf(), xOnlyPub(sk))
    }

    fun xOnlyPub(sk: ByteArray): ByteArray {
        val p = g.multiply(BigInteger(1, sk)).normalize()
        return p32(p.affineXCoord.toBigInteger())
    }

    /** Even-y adjusted secret used by BIP-340 signing. */
    private fun evenSecret(sk: ByteArray): Pair<BigInteger, ByteArray> {
        val d0 = BigInteger(1, sk)
        val p = g.multiply(d0).normalize()
        val d = if (isOdd(p)) n.subtract(d0) else d0
        return d to p32(p.affineXCoord.toBigInteger())
    }

    fun sign(sk: ByteArray, message32: ByteArray): ByteArray {
        require(message32.size == 32)
        val (d, px) = evenSecret(sk)
        val aux = ByteArray(32).also { rng.nextBytes(it) }
        val t = xor32(p32(d), taggedHash("BIP0340/aux", aux))
        val k0 = BigInteger(1, taggedHash("BIP0340/nonce", t + px + message32)).mod(n)
        require(k0 != BigInteger.ZERO) { "nonce failure" }
        val r = g.multiply(k0).normalize()
        val k = if (isOdd(r)) n.subtract(k0) else k0
        val rx = p32(r.affineXCoord.toBigInteger())
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rx + px + message32)).mod(n)
        val s = k.add(e.multiply(d)).mod(n)
        return rx + p32(s)
    }

    fun verify(pubkeyXOnly: ByteArray, message32: ByteArray, sig: ByteArray): Boolean {
        if (pubkeyXOnly.size != 32 || message32.size != 32 || sig.size != 64) return false
        return try {
            val p = liftX(BigInteger(1, pubkeyXOnly)) ?: return false
            val rx = BigInteger(1, sig.copyOfRange(0, 32))
            val s = BigInteger(1, sig.copyOfRange(32, 64))
            if (rx.signum() == 0 || rx >= spec.curve.field.characteristic) return false
            if (s.signum() == 0 || s >= n) return false
            val e = BigInteger(1, taggedHash("BIP0340/challenge", sig.copyOfRange(0, 32) + pubkeyXOnly + message32)).mod(n)
            val r = g.multiply(s).add(p.multiply(n.subtract(e))).normalize()
            if (r.isInfinity || isOdd(r)) return false
            r.affineXCoord.toBigInteger() == rx
        } catch (_: Exception) {
            false
        }
    }

    /**
     * NIP-44 ECDH: x-coordinate of `sk * lift_x(peer)`.
     * Peer is a BIP-340 x-only pubkey (even y).
     */
    fun ecdhX(sk: ByteArray, peerXOnly: ByteArray): ByteArray {
        val p = liftX(BigInteger(1, peerXOnly)) ?: throw IllegalArgumentException("bad peer pubkey")
        val shared = p.multiply(BigInteger(1, sk)).normalize()
        require(!shared.isInfinity) { "ECDH infinity" }
        return p32(shared.affineXCoord.toBigInteger())
    }

    fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(tagHash + tagHash + msg)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    private fun liftX(x: BigInteger): ECPoint? {
        return try {
            val point = spec.curve.decodePoint(byteArrayOf(0x02) + p32(x)).normalize()
            if (point.isInfinity) null else point
        } catch (_: Exception) {
            null
        }
    }

    private fun isOdd(p: ECPoint): Boolean = p.affineYCoord.toBigInteger().testBit(0)

    private fun p32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val srcOff = maxOf(0, raw.size - 32)
        val dstOff = 32 - (raw.size - srcOff)
        System.arraycopy(raw, srcOff, out, dstOff, raw.size - srcOff)
        return out
    }

    private fun xor32(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(32)
        for (i in 0 until 32) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return out
    }
}
