// SPDX-License-Identifier: MIT
package dev.morpbox.app.blossom

import dev.morpbox.app.crypto.sha256
import dev.morpbox.app.crypto.toHex
import dev.morpbox.app.protocol.MorpKinds
import dev.morpbox.app.protocol.NostrEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64

class BlossomClient(private val http: OkHttpClient = OkHttpClient()) {
    fun sha256Hex(data: ByteArray): String = sha256(data).toHex()
    fun blobUrl(server: String, sha: String): String = server.trimEnd('/') + "/" + sha

    fun parseServerList(e: NostrEvent): List<String> {
        require(e.kind == MorpKinds.BLOSSOM_SERVERS)
        return e.tags.filter { it.size >= 2 && it[0] == "server" }.map { it[1] }
    }

    fun buildUploadAuth(
        sign: (NostrEvent) -> NostrEvent,
        sha: String,
        pubkey: String,
        ttlS: Long = 600,
    ): NostrEvent {
        val now = System.currentTimeMillis() / 1000
        val e = NostrEvent(
            pubkey, now, MorpKinds.BLOSSOM_AUTH,
            listOf(listOf("t", "upload"), listOf("x", sha), listOf("expiration", (now + ttlS).toString())),
            "Upload blob",
        )
        return sign(e)
    }

    fun authHeader(e: NostrEvent): String =
        "Nostr " + Base64.getEncoder().encodeToString(e.toJson().toByteArray())

    data class BlobDescriptor(val url: String, val sha: String, val size: Long, val mime: String?)

    fun upload(server: String, data: ByteArray, mime: String, auth: NostrEvent): BlobDescriptor {
        val sha = sha256Hex(data)
        val req = Request.Builder().url(server.trimEnd('/') + "/upload")
            .put(data.toRequestBody(mime.toMediaType()))
            .header("Authorization", authHeader(auth)).build()
        http.newCall(req).execute().use { r ->
            require(r.isSuccessful) { "blossom PUT ${r.code}" }
            val o = JSONObject(r.body!!.string())
            return BlobDescriptor(
                o.getString("url"), o.getString("sha256"),
                o.optLong("size", data.size.toLong()), o.optString("type", mime),
            )
        }
    }
}
