// SPDX-License-Identifier: MIT
package dev.morpbox.app.nostr

import dev.morpbox.app.protocol.MorpKinds
import dev.morpbox.app.protocol.NostrEvent
import org.json.JSONObject

/** In-process NIP-01 store for mesh-island cache and local REQ. */
class EmbeddedRelay {
    private val lock = Any()
    private val byId = mutableMapOf<String, NostrEvent>()
    private val replaceable = mutableMapOf<Pair<Int, String>, NostrEvent>()

    fun store(e: NostrEvent): Boolean {
        if (!e.verify()) return false
        if (MorpKinds.isEphemeral(e.kind)) return true
        synchronized(lock) {
            if (byId.containsKey(e.id)) return true
            if (MorpKinds.isReplaceable(e.kind)) {
                val k = e.kind to e.pubkey
                val cur = replaceable[k]
                if (cur != null && cur.createdAt >= e.createdAt) return false
                if (cur != null) byId.remove(cur.id)
                replaceable[k] = e
            }
            byId[e.id] = e
        }
        return true
    }

    fun get(id: String): NostrEvent? = synchronized(lock) { byId[id] }

    fun query(filter: JSONObject, limit: Int = 500): List<NostrEvent> = synchronized(lock) {
        val ids = filter.optJSONArray("ids")?.let { (0 until it.length()).map { i -> it.getString(i) }.toSet() }
        val authors = filter.optJSONArray("authors")?.let { (0 until it.length()).map { i -> it.getString(i) }.toSet() }
        val kinds = filter.optJSONArray("kinds")?.let { (0 until it.length()).map { i -> it.getInt(i) }.toSet() }
        val since = if (filter.has("since")) filter.getLong("since") else null
        val until = if (filter.has("until")) filter.getLong("until") else null
        val tagQs = mutableMapOf<String, Set<String>>()
        for (k in listOf("#e", "#p")) {
            filter.optJSONArray(k)?.let { a ->
                tagQs[k.drop(1)] = (0 until a.length()).map { i -> a.getString(i) }.toSet()
            }
        }
        byId.values.filter { e ->
            (ids == null || e.id in ids) &&
                (authors == null || e.pubkey in authors) &&
                (kinds == null || e.kind in kinds) &&
                (since == null || e.createdAt >= since) &&
                (until == null || e.createdAt <= until) &&
                tagQs.all { (tag, vals) ->
                    e.tags.any { it.isNotEmpty() && it[0] == tag && it.size > 1 && it[1] in vals }
                }
        }.sortedByDescending { it.createdAt }.take(minOf(limit, filter.optInt("limit", 500)))
    }

    fun recentKind1(limit: Int = 50): List<NostrEvent> = synchronized(lock) {
        byId.values.filter { it.kind == MorpKinds.TEXT_NOTE }.sortedByDescending { it.createdAt }.take(limit)
    }

    fun count(): Int = synchronized(lock) { byId.size }
}
