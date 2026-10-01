// SPDX-License-Identifier: MIT
package dev.morpbox.app.data

import android.content.Context
import dev.morpbox.app.protocol.MorpKinds
import dev.morpbox.app.protocol.NostrEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline-first event / chat / outbox store. JSONL files under app-private storage.
 * Small enough for a tactical messenger cache; wiped by SecureWipe deleting filesDir.
 */
class LocalStore(context: Context) {

    private val dir = File(context.filesDir, "store").also { it.mkdirs() }
    private val events = ConcurrentHashMap<String, StoredEvent>()
    private val profiles = ConcurrentHashMap<String, StoredProfile>()
    private val messages = ConcurrentHashMap<String, MutableList<StoredMessage>>()
    private val rooms = ConcurrentHashMap<String, StoredRoom>()
    private val outbox = ConcurrentHashMap<String, OutboxItem>()
    private val contacts = ConcurrentHashMap<String, StoredContact>()

    init {
        loadEvents()
        loadMessages()
        loadOutbox()
        loadContacts()
        loadRooms()
        loadProfiles()
    }

    data class StoredEvent(
        val event: NostrEvent,
        val via: String,
        val receivedAt: Long = System.currentTimeMillis(),
    )

    data class StoredProfile(
        val pubkey: String,
        val name: String,
        val about: String,
        val lud16: String,
        val picture: String = "",
        val raw: NostrEvent? = null,
    )

    data class StoredMessage(
        val id: String,
        val roomId: String,
        val sender: String,
        val ts: Long,
        val text: String,
        val via: String,
        val outbound: Boolean,
    )

    data class StoredRoom(
        val id: String,
        val kind: String, // dm | group | geo | named
        val title: String,
        val members: List<String>,
        val lastTs: Long,
        val lastPreview: String,
    )

    data class StoredContact(
        val pubkey: String,
        val petname: String = "",
        val lastSeen: Long = 0,
    )

    enum class Delivery { QUEUED, ON_MESH, PUBLISHED, DELIVERED, CONFIRMED, FAILED }

    data class OutboxItem(
        val id: String,
        val op: String,
        val signedJson: String,
        val relays: List<String>,
        val needCaps: List<String>,
        var state: Delivery,
        var retries: Int,
        var updatedAt: Long,
        val extra: String = "",
    )

    fun putEvent(e: NostrEvent, via: String): Boolean {
        if (e.id.isEmpty()) return false
        if (MorpKinds.isEphemeral(e.kind)) return true
        if (MorpKinds.isReplaceable(e.kind)) {
            val existing = events.values.firstOrNull { it.event.kind == e.kind && it.event.pubkey == e.pubkey }
            if (existing != null && existing.event.createdAt >= e.createdAt) return false
            if (existing != null) events.remove(existing.event.id)
        }
        if (events.putIfAbsent(e.id, StoredEvent(e, via)) != null) return false
        if (e.kind == MorpKinds.META) ingestProfile(e)
        persistEvents()
        return true
    }

    // keep compiler happy: take the newest 2000 by created_at
    private fun newestEvents(): List<StoredEvent> =
        events.values.sortedByDescending { it.event.createdAt }.take(2000)

    fun feed(limit: Int = 200): List<StoredEvent> =
        events.values.filter { it.event.kind == MorpKinds.TEXT_NOTE || it.event.kind == MorpKinds.REPOST }
            .sortedByDescending { it.event.createdAt }
            .take(limit)

    fun event(id: String): StoredEvent? = events[id]

    fun profile(pubkey: String): StoredProfile? = profiles[pubkey]

    fun displayName(pubkey: String): String {
        val p = profiles[pubkey]
        if (p != null && p.name.isNotBlank()) return p.name
        val c = contacts[pubkey]
        if (c != null && c.petname.isNotBlank()) return c.petname
        return pubkey.take(8)
    }

    private fun ingestProfile(e: NostrEvent) {
        try {
            val o = JSONObject(e.content)
            profiles[e.pubkey] = StoredProfile(
                pubkey = e.pubkey,
                name = o.optString("display_name").ifBlank { o.optString("name") },
                about = o.optString("about"),
                lud16 = o.optString("lud16").ifBlank { o.optString("nip05") },
                picture = o.optString("picture"),
                raw = e,
            )
            persistProfiles()
        } catch (_: Exception) { }
    }

    fun upsertContact(pubkey: String, petname: String = "") {
        val cur = contacts[pubkey]
        contacts[pubkey] = StoredContact(pubkey, petname.ifBlank { cur?.petname ?: "" }, System.currentTimeMillis())
        persistContacts()
    }

    fun allContacts(): List<StoredContact> = contacts.values.sortedBy { it.petname.ifBlank { it.pubkey } }

    fun putMessage(m: StoredMessage) {
        val list = messages.getOrPut(m.roomId) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.id == m.id }) list += m
        }
        val room = rooms[m.roomId] ?: StoredRoom(m.roomId, if (m.roomId.startsWith("g:")) "group" else "dm", m.roomId, emptyList(), m.ts, m.text)
        rooms[m.roomId] = room.copy(lastTs = m.ts, lastPreview = m.text.take(80))
        persistMessages()
        persistRooms()
    }

    fun history(roomId: String): List<StoredMessage> = messages[roomId]?.sortedBy { it.ts } ?: emptyList()

    fun allRooms(): List<StoredRoom> = rooms.values.sortedByDescending { it.lastTs }

    fun upsertRoom(r: StoredRoom) {
        rooms[r.id] = r
        persistRooms()
    }

    fun enqueue(item: OutboxItem) {
        outbox[item.id] = item
        persistOutbox()
    }

    fun updateOutbox(id: String, state: Delivery, retries: Int? = null) {
        val cur = outbox[id] ?: return
        cur.state = state
        if (retries != null) cur.retries = retries
        cur.updatedAt = System.currentTimeMillis()
        persistOutbox()
    }

    fun pendingOutbox(): List<OutboxItem> =
        outbox.values.filter { it.state == Delivery.QUEUED || it.state == Delivery.ON_MESH || it.state == Delivery.FAILED }
            .sortedBy { it.updatedAt }

    fun allOutbox(): List<OutboxItem> = outbox.values.sortedByDescending { it.updatedAt }

    fun clearAll() {
        events.clear(); profiles.clear(); messages.clear(); rooms.clear(); outbox.clear(); contacts.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    // ---- persistence -------------------------------------------------------
    private fun file(name: String) = File(dir, name)

    private fun loadEvents() = readArray("events.json") { o ->
        val e = NostrEvent.fromJson(o.getJSONObject("e"))
        events[e.id] = StoredEvent(e, o.optString("via", "internet"), o.optLong("t"))
    }

    private fun persistEvents() = writeArray("events.json", newestEvents()) { s ->
        JSONObject().put("e", s.event.toJsonObject()).put("via", s.via).put("t", s.receivedAt)
    }

    private fun loadProfiles() = readArray("profiles.json") { o ->
        profiles[o.getString("pubkey")] = StoredProfile(
            o.getString("pubkey"), o.optString("name"), o.optString("about"),
            o.optString("lud16"), o.optString("picture"),
        )
    }

    private fun persistProfiles() = writeArray("profiles.json", profiles.values) { p ->
        JSONObject().put("pubkey", p.pubkey).put("name", p.name).put("about", p.about)
            .put("lud16", p.lud16).put("picture", p.picture)
    }

    private fun loadMessages() = readArray("messages.json") { o ->
        val m = StoredMessage(
            o.getString("id"), o.getString("room"), o.getString("sender"),
            o.getLong("ts"), o.getString("text"), o.optString("via"), o.optBoolean("out"),
        )
        messages.getOrPut(m.roomId) { mutableListOf() }.add(m)
    }

    private fun persistMessages() {
        val all = messages.values.flatten().sortedBy { it.ts }.takeLast(4000)
        writeArray("messages.json", all) { m ->
            JSONObject().put("id", m.id).put("room", m.roomId).put("sender", m.sender)
                .put("ts", m.ts).put("text", m.text).put("via", m.via).put("out", m.outbound)
        }
    }

    private fun loadRooms() = readArray("rooms.json") { o ->
        val members = o.optJSONArray("members") ?: JSONArray()
        rooms[o.getString("id")] = StoredRoom(
            o.getString("id"), o.optString("kind", "dm"), o.optString("title"),
            (0 until members.length()).map { members.getString(it) },
            o.optLong("lastTs"), o.optString("preview"),
        )
    }

    private fun persistRooms() = writeArray("rooms.json", rooms.values) { r ->
        JSONObject().put("id", r.id).put("kind", r.kind).put("title", r.title)
            .put("members", JSONArray(r.members)).put("lastTs", r.lastTs).put("preview", r.lastPreview)
    }

    private fun loadOutbox() = readArray("outbox.json") { o ->
        val relays = o.optJSONArray("relays") ?: JSONArray()
        val caps = o.optJSONArray("caps") ?: JSONArray()
        val item = OutboxItem(
            o.getString("id"), o.getString("op"), o.getString("json"),
            (0 until relays.length()).map { relays.getString(it) },
            (0 until caps.length()).map { caps.getString(it) },
            Delivery.valueOf(o.optString("state", "QUEUED")),
            o.optInt("retries"), o.optLong("t"), o.optString("extra"),
        )
        outbox[item.id] = item
    }

    private fun persistOutbox() = writeArray("outbox.json", outbox.values) { i ->
        JSONObject().put("id", i.id).put("op", i.op).put("json", i.signedJson)
            .put("relays", JSONArray(i.relays)).put("caps", JSONArray(i.needCaps))
            .put("state", i.state.name).put("retries", i.retries).put("t", i.updatedAt)
            .put("extra", i.extra)
    }

    private fun loadContacts() = readArray("contacts.json") { o ->
        contacts[o.getString("pubkey")] = StoredContact(o.getString("pubkey"), o.optString("petname"), o.optLong("seen"))
    }

    private fun persistContacts() = writeArray("contacts.json", contacts.values) { c ->
        JSONObject().put("pubkey", c.pubkey).put("petname", c.petname).put("seen", c.lastSeen)
    }

    private fun readArray(name: String, each: (JSONObject) -> Unit) {
        val f = file(name)
        if (!f.exists()) return
        try {
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) each(a.getJSONObject(i))
        } catch (_: Exception) { }
    }

    private fun <T> writeArray(name: String, items: Collection<T>, map: (T) -> JSONObject) {
        try {
            val a = JSONArray()
            items.forEach { a.put(map(it)) }
            file(name).writeText(a.toString())
        } catch (_: Exception) { }
    }
}
