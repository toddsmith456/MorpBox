// SPDX-License-Identifier: MIT
package dev.morpbox

import dev.morpbox.app.crypto.Bech32
import dev.morpbox.app.crypto.Nip44
import dev.morpbox.app.crypto.Secp256k1
import dev.morpbox.app.crypto.toHex
import dev.morpbox.app.nostr.Nip17
import dev.morpbox.app.protocol.MorpFrame
import dev.morpbox.app.protocol.NostrBuilders
import dev.morpbox.app.protocol.NostrEvent
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

class CryptoTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun provider() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    @Test
    fun bech32RoundTrip() {
        val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        val npub = Bech32.hexToNpub(hex)
        assertTrue(npub.startsWith("npub1"))
        assertEquals(hex, Bech32.npubToHex(npub))
    }

    @Test
    fun bip340SignVerify() {
        val kp = Secp256k1.generate()
        val msg = ByteArray(32) { 7 }
        val sig = Secp256k1.sign(kp.secret, msg)
        assertEquals(64, sig.size)
        assertTrue(Secp256k1.verify(kp.pubkeyXOnly, msg, sig))
        assertFalse(Secp256k1.verify(kp.pubkeyXOnly, ByteArray(32) { 8 }, sig))
    }

    @Test
    fun nostrEventSignVerify() {
        val kp = Secp256k1.generate()
        val e = NostrBuilders.kind1(kp.pubkeyHex, "hello mesh").signed(kp.secret)
        assertEquals(64, e.id.length)
        assertTrue(e.verify())
        assertEquals(e.id, e.computeId())
    }

    @Test
    fun nip44RoundTrip() {
        val a = Secp256k1.generate()
        val b = Secp256k1.generate()
        val ck = Nip44.conversationKey(a.secret, b.pubkeyXOnly)
        val ck2 = Nip44.conversationKey(b.secret, a.pubkeyXOnly)
        assertEquals(ck.toHex(), ck2.toHex())
        val payload = Nip44.encrypt(ck, "secret note ⚡")
        assertEquals("secret note ⚡", Nip44.decrypt(ck2, payload))
    }

    @Test
    fun nip17GiftWrap() {
        val alice = Secp256k1.generate()
        val bob = Secp256k1.generate()
        val rumor = Nip17.rumor(alice.pubkeyHex, "hi bob", bob.pubkeyHex)
        val wrap = Nip17.wrap(alice.secret, alice.pubkeyHex, bob.pubkeyHex, rumor)
        assertEquals(1059, wrap.kind)
        assertTrue(wrap.verify())
        val opened = Nip17.open(bob.secret, bob.pubkeyHex, wrap)
        assertEquals("hi bob", opened!!.content)
        assertEquals(alice.pubkeyHex, opened.pubkey)
        assertEquals(null, Nip17.open(alice.secret, alice.pubkeyHex, wrap))
    }

    @Test
    fun morpFrameRoundTrip() {
        val payload = "kind-1 over bluetooth".toByteArray()
        val frames = MorpFrame.frame(payload, mtu = 180)
        assertTrue(frames.isNotEmpty())
        val parsed = MorpFrame.parse(frames.first())
        assertEquals(0, parsed.seq)
        assertTrue(parsed.isFlood)
    }

    @Test
    fun nip44PaddingTiers() {
        assertEquals(32, Nip44.calcPaddedLen(1))
        assertEquals(32, Nip44.calcPaddedLen(32))
        assertEquals(64, Nip44.calcPaddedLen(33))
    }
}
