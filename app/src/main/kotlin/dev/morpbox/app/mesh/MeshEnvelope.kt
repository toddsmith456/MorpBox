// SPDX-License-Identifier: MIT
package dev.morpbox.app.mesh

import dev.morpbox.app.protocol.NostrEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Application payload carried inside MORP-PKT DATA frames (BLE) and MeshCore
 * channel datagrams. Tiny JSON so any MorpBox peer can decode it.
 */
sealed class MeshMessage {
    data class Event(val event: NostrEvent) : MeshMessage()
    data class Beacon(
        val hopHex: String,
        val devPubHex: String,
        val npub: String,
        val caps: List<String>,
        val load: Int,
        val relays: List<String>,
        val name: String,
    ) : MeshMessage()
    data class Receipt(val reqId: String, val ok: Boolean) : MeshMessage()
}

object MeshEnvelope {
    fun encode(msg: MeshMessage): ByteArray = when (msg) {
        is MeshMessage.Event -> JSONObject()
            .put("v", 1).put("t", "evt").put("e", msg.event.toJsonObject()).toString()
        is MeshMessage.Beacon -> JSONObject()
            .put("v", 1).put("t", "adv")
            .put("hop", msg.hopHex).put("dev", msg.devPubHex).put("npub", msg.npub)
            .put("caps", JSONArray(msg.caps)).put("load", msg.load)
            .put("relays", JSONArray(msg.relays)).put("name", msg.name)
            .toString()
        is MeshMessage.Receipt -> JSONObject()
            .put("v", 1).put("t", "ack").put("id", msg.reqId).put("ok", msg.ok).toString()
    }.toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): MeshMessage? = try {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        if (o.optInt("v") != 1) return null
        when (o.optString("t")) {
            "evt" -> MeshMessage.Event(NostrEvent.fromJson(o.getJSONObject("e")))
            "adv" -> {
                val caps = o.optJSONArray("caps") ?: JSONArray()
                val relays = o.optJSONArray("relays") ?: JSONArray()
                MeshMessage.Beacon(
                    hopHex = o.getString("hop"),
                    devPubHex = o.getString("dev"),
                    npub = o.optString("npub"),
                    caps = (0 until caps.length()).map { caps.getString(it) },
                    load = o.optInt("load"),
                    relays = (0 until relays.length()).map { relays.getString(it) },
                    name = o.optString("name"),
                )
            }
            "ack" -> MeshMessage.Receipt(o.getString("id"), o.optBoolean("ok", true))
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
