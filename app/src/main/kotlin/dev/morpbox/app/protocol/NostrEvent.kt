// SPDX-License-Identifier: MIT
package dev.morpbox.app.protocol

import dev.morpbox.app.crypto.Secp256k1
import dev.morpbox.app.crypto.hexToBytes
import dev.morpbox.app.crypto.sha256
import dev.morpbox.app.crypto.toHex
import org.json.JSONArray
import org.json.JSONObject

/**
 * NIP-01 event. Id is SHA-256 of the canonical array; signature is BIP-340 over the id.
 */
data class NostrEvent(
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val id: String = "",
    val sig: String = "",
) {
    fun canonical(): ByteArray {
        val tagsJson = NostrJson.arrayOfTagRows(tags)
        val json = "[0,${NostrJson.quote(pubkey)},$createdAt,$kind,$tagsJson,${NostrJson.quote(content)}]"
        return json.toByteArray(Charsets.UTF_8)
    }

    fun computeId(): String = sha256(canonical()).toHex()

    fun withId(): NostrEvent = if (id.isNotEmpty()) this else copy(id = computeId())

    fun signed(secret: ByteArray): NostrEvent {
        val eventId = computeId()
        val sig = Secp256k1.sign(secret, eventId.hexToBytes()).toHex()
        return copy(id = eventId, sig = sig)
    }

    fun verify(): Boolean {
        if (id.isEmpty() || sig.isEmpty()) return false
        if (computeId() != id) return false
        return try {
            Secp256k1.verify(pubkey.hexToBytes(), id.hexToBytes(), sig.hexToBytes())
        } catch (_: Exception) {
            false
        }
    }

    fun toJsonObject(): JSONObject = JSONObject()
        .put("id", id)
        .put("pubkey", pubkey)
        .put("created_at", createdAt)
        .put("kind", kind)
        .put("tags", JSONArray(tags.map { JSONArray(it) }))
        .put("content", content)
        .put("sig", sig)

    fun toJson(): String = toJsonObject().toString()

    fun tagValues(name: String): List<String> = tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }

    fun firstTag(name: String): String? = tagValues(name).firstOrNull()

    companion object {
        fun fromJson(raw: String): NostrEvent = fromJson(JSONObject(raw))

        fun fromJson(o: JSONObject): NostrEvent {
            val tags = mutableListOf<List<String>>()
            val ta = o.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until ta.length()) {
                val row = ta.getJSONArray(i)
                tags += (0 until row.length()).map { row.getString(it) }
            }
            return NostrEvent(
                pubkey = o.getString("pubkey"),
                createdAt = o.getLong("created_at"),
                kind = o.getInt("kind"),
                tags = tags,
                content = o.getString("content"),
                id = o.optString("id", ""),
                sig = o.optString("sig", ""),
            )
        }
    }
}

object NostrBuilders {
    fun now(): Long = System.currentTimeMillis() / 1000

    fun kind1(pubkey: String, content: String, tags: List<List<String>> = emptyList()): NostrEvent =
        NostrEvent(pubkey, now(), MorpKinds.TEXT_NOTE, tags, content)

    fun reply(pubkey: String, content: String, replyTo: NostrEvent, relayHint: String = ""): NostrEvent {
        val root = replyTo.firstTag("e") ?: replyTo.id
        val tags = listOf(
            listOf("e", root, relayHint, "root"),
            listOf("e", replyTo.id, relayHint, "reply"),
            listOf("p", replyTo.pubkey, relayHint),
        )
        return NostrEvent(pubkey, now(), MorpKinds.TEXT_NOTE, tags, content)
    }

    fun repost(pubkey: String, target: NostrEvent, relayHint: String = ""): NostrEvent =
        NostrEvent(
            pubkey, now(), MorpKinds.REPOST,
            listOf(listOf("e", target.id, relayHint), listOf("p", target.pubkey, relayHint)),
            target.toJson(),
        )

    fun quote(pubkey: String, content: String, target: NostrEvent, relayHint: String = ""): NostrEvent {
        val body = if (content.isEmpty()) "nostr:${target.id}" else "$content\nnostr:${target.id}"
        return NostrEvent(
            pubkey, now(), MorpKinds.TEXT_NOTE,
            listOf(listOf("q", target.id, relayHint, target.pubkey), listOf("p", target.pubkey)),
            body,
        )
    }

    fun reaction(pubkey: String, target: NostrEvent, content: String = "+"): NostrEvent =
        NostrEvent(
            pubkey, now(), MorpKinds.REACTION,
            listOf(listOf("e", target.id), listOf("p", target.pubkey)),
            content,
        )

    fun metadata(pubkey: String, name: String, about: String, lud16: String?, xmr: String?): NostrEvent {
        val o = JSONObject().put("name", name).put("about", about).put("display_name", name)
        if (!lud16.isNullOrBlank()) o.put("lud16", lud16)
        val tags = mutableListOf<List<String>>()
        if (!xmr.isNullOrBlank()) tags += listOf("payto", "monero", xmr)
        return NostrEvent(pubkey, now(), MorpKinds.META, tags, o.toString())
    }
}
