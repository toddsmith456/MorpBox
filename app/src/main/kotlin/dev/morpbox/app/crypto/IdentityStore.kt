// SPDX-License-Identifier: MIT
package dev.morpbox.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import org.json.JSONArray

/**
 * Identity + settings, sealed with Tink AES-GCM whose master key lives in Android Keystore.
 * Generates a secp256k1 nsec on first launch. Transport (X25519) key is derived separately
 * and stored alongside for MORP onion / RNS.
 */
class IdentityStore(context: Context) {

    data class Profile(
        val name: String = "",
        val about: String = "",
        val lud16: String = "",
        val xmrAddress: String = "",
        val nwcUri: String = "",
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val aead: Aead = run {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, KEYSET, PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    val keys: Secp256k1.KeyPair by lazy { loadOrCreate() }

    val pubkeyHex: String get() = keys.pubkeyHex
    val npub: String get() = keys.npub

    var displayName: String
        get() = prefs.getString(NAME, "") ?: ""
        set(value) { prefs.edit().putString(NAME, value).apply() }

    var about: String
        get() = prefs.getString(ABOUT, "") ?: ""
        set(value) { prefs.edit().putString(ABOUT, value).apply() }

    var lud16: String
        get() = prefs.getString(LUD16, "") ?: ""
        set(value) { prefs.edit().putString(LUD16, value).apply() }

    var xmrAddress: String
        get() = prefs.getString(XMR, "") ?: ""
        set(value) { prefs.edit().putString(XMR, value).apply() }

    var nwcUri: String
        get() = prefs.getString(NWC, "") ?: ""
        set(value) { prefs.edit().putString(NWC, value).apply() }

    var relays: List<String>
        get() {
            val raw = prefs.getString(RELAYS, null)
            if (raw.isNullOrBlank()) return DEFAULT_RELAYS
            return try {
                val a = JSONArray(raw)
                (0 until a.length()).map { a.getString(it) }.filter { it.startsWith("wss://") }
            } catch (_: Exception) {
                DEFAULT_RELAYS
            }
        }
        set(value) {
            val a = JSONArray()
            value.forEach { a.put(it) }
            prefs.edit().putString(RELAYS, a.toString()).apply()
        }

    var routePolicy: String
        get() = prefs.getString(POLICY, "auto") ?: "auto"
        set(value) { prefs.edit().putString(POLICY, value).apply() }

    var actAsPortal: Boolean
        get() = prefs.getBoolean(PORTAL, true)
        set(value) { prefs.edit().putBoolean(PORTAL, value).apply() }

    var meshOn: Boolean
        get() = prefs.getBoolean(MESH, false)
        set(value) { prefs.edit().putBoolean(MESH, value).apply() }

    var namedRoom: String
        get() = prefs.getString(ROOM, "morp") ?: "morp"
        set(value) { prefs.edit().putString(ROOM, value).apply() }

    fun profile(): Profile = Profile(displayName, about, lud16, xmrAddress, nwcUri)

    fun exportNsec(): String = keys.nsec

    fun wipe() {
        prefs.edit().clear().apply()
    }

    private fun loadOrCreate(): Secp256k1.KeyPair {
        val blob = prefs.getString(NSEC, null)
        if (blob != null) {
            return try {
                val ct = Base64.decode(blob, Base64.NO_WRAP)
                val sk = aead.decrypt(ct, AAD)
                Secp256k1.fromSecret(sk)
            } catch (_: Exception) {
                createFresh()
            }
        }
        return createFresh()
    }

    private fun createFresh(): Secp256k1.KeyPair {
        val kp = Secp256k1.generate()
        val ct = aead.encrypt(kp.secret, AAD)
        prefs.edit().putString(NSEC, Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
        return kp
    }

    fun importNsec(nsecOrHex: String): Secp256k1.KeyPair {
        val sk = when {
            nsecOrHex.startsWith("nsec1") -> Bech32.nsecToBytes(nsecOrHex)
            else -> nsecOrHex.hexToBytes()
        }
        require(sk.size == 32)
        val kp = Secp256k1.fromSecret(sk)
        val ct = aead.encrypt(kp.secret, AAD)
        prefs.edit().putString(NSEC, Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
        return kp
    }

    companion object {
        private const val PREFS = "morpbox"
        private const val KEYSET = "morpbox_keyset"
        private const val MASTER = "android-keystore://morpbox_master"
        private const val NSEC = "nsec_sealed"
        private const val NAME = "name"
        private const val ABOUT = "about"
        private const val LUD16 = "lud16"
        private const val XMR = "xmr"
        private const val NWC = "nwc"
        private const val RELAYS = "relays"
        private const val POLICY = "policy"
        private const val PORTAL = "portal"
        private const val MESH = "mesh"
        private const val ROOM = "room"
        private val AAD = "morpbox-nsec-v1".toByteArray()

        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://offchain.pub",
        )
    }
}
