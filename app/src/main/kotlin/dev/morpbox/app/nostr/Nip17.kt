// SPDX-License-Identifier: MIT
package dev.morpbox.app.nostr

import dev.morpbox.app.crypto.Nip44
import dev.morpbox.app.crypto.Secp256k1
import dev.morpbox.app.crypto.hexToBytes
import dev.morpbox.app.protocol.MorpKinds
import dev.morpbox.app.protocol.NostrEvent
import dev.morpbox.app.protocol.NostrBuilders
import org.json.JSONArray
import org.json.JSONObject

/**
 * NIP-17 private messages: unsigned kind-14 rumor → kind-13 seal (NIP-44 to recipient,
 * signed by sender) → kind-1059 gift wrap (NIP-44 to recipient, signed by ephemeral key).
 *
 * Group chats fan the same rumor out to each member (cap 12).
 */
object Nip17 {

    data class Rumor(
        val kind: Int,
        val pubkey: String,
        val createdAt: Long,
        val tags: List<List<String>>,
        val content: String,
    ) {
        fun toJson(): String = JSONObject()
            .put("kind", kind)
            .put("pubkey", pubkey)
            .put("created_at", createdAt)
            .put("tags", JSONArray(tags.map { JSONArray(it) }))
            .put("content", content)
            .toString()

        companion object {
            fun fromJson(raw: String): Rumor {
                val o = JSONObject(raw)
                val tags = mutableListOf<List<String>>()
                val ta = o.optJSONArray("tags") ?: JSONArray()
                for (i in 0 until ta.length()) {
                    val row = ta.getJSONArray(i)
                    tags += (0 until row.length()).map { row.getString(it) }
                }
                return Rumor(
                    kind = o.getInt("kind"),
                    pubkey = o.getString("pubkey"),
                    createdAt = o.getLong("created_at"),
                    tags = tags,
                    content = o.getString("content"),
                )
            }
        }
    }

    fun rumor(
        senderHex: String,
        content: String,
        recipientHex: String,
        room: String = "",
        extraTags: List<List<String>> = emptyList(),
    ): Rumor {
        val tags = mutableListOf(listOf("p", recipientHex))
        if (room.isNotBlank()) tags += listOf("d", room)
        tags += extraTags
        return Rumor(MorpKinds.CHAT_RUMOR, senderHex, NostrBuilders.now(), tags, content)
    }

    fun wrap(
        senderSk: ByteArray,
        senderPub: String,
        recipientPub: String,
        rumor: Rumor,
    ): NostrEvent {
        val conversation = Nip44.conversationKey(senderSk, recipientPub.hexToBytes())
        val seal = NostrEvent(
            pubkey = senderPub,
            createdAt = rumor.createdAt,
            kind = MorpKinds.SEAL,
            tags = emptyList(),
            content = Nip44.encrypt(conversation, rumor.toJson()),
        ).signed(senderSk)

        val wrapKey = Secp256k1.generate()
        val wrapConversation = Nip44.conversationKey(wrapKey.secret, recipientPub.hexToBytes())
        val jitter = (0..600).random()
        return NostrEvent(
            pubkey = wrapKey.pubkeyHex,
            createdAt = NostrBuilders.now() - jitter,
            kind = MorpKinds.GIFT_WRAP,
            tags = listOf(listOf("p", recipientPub)),
            content = Nip44.encrypt(wrapConversation, seal.toJson()),
        ).signed(wrapKey.secret)
    }

    fun open(recipientSk: ByteArray, recipientPub: String, wrap: NostrEvent): Rumor? {
        if (wrap.kind != MorpKinds.GIFT_WRAP) return null
        if (wrap.firstTag("p") != null && wrap.firstTag("p") != recipientPub) return null
        return try {
            val wrapKey = Nip44.conversationKey(recipientSk, wrap.pubkey.hexToBytes())
            val sealJson = Nip44.decrypt(wrapKey, wrap.content)
            val seal = NostrEvent.fromJson(sealJson)
            if (!seal.verify() || seal.kind != MorpKinds.SEAL) return null
            val sealKey = Nip44.conversationKey(recipientSk, seal.pubkey.hexToBytes())
            Rumor.fromJson(Nip44.decrypt(sealKey, seal.content))
        } catch (_: Exception) {
            null
        }
    }

    fun wrapGroup(
        senderSk: ByteArray,
        senderPub: String,
        members: List<String>,
        content: String,
        room: String,
    ): List<NostrEvent> {
        require(members.size <= MorpKinds.GROUP_MEMBER_CAP) { "group cap ${MorpKinds.GROUP_MEMBER_CAP}" }
        return members.map { member ->
            val r = rumor(senderPub, content, member, room)
            wrap(senderSk, senderPub, member, r)
        }
    }
}
