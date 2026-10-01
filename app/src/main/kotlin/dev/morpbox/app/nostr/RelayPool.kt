// SPDX-License-Identifier: MIT
package dev.morpbox.app.nostr

import dev.morpbox.app.protocol.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RelayConn(
    val url: String,
    client: OkHttpClient,
    private val onEvent: (subId: String, e: NostrEvent) -> Unit,
    private val onState: (up: Boolean) -> Unit,
) : WebSocketListener() {
    private val ws: WebSocket = client.newWebSocket(Request.Builder().url(url).build(), this)
    private val okWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    @Volatile var up: Boolean = false
        private set

    override fun onOpen(webSocket: WebSocket, response: Response) {
        up = true
        onState(true)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        up = false
        onState(false)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        up = false
        onState(false)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val a = JSONArray(text)
            when (a.getString(0)) {
                "EVENT" -> {
                    val e = NostrEvent.fromJson(a.getJSONObject(2))
                    if (e.verify()) onEvent(a.getString(1), e)
                }
                "OK" -> okWaiters.remove(a.getString(1))?.complete(a.optBoolean(2, false))
                "EOSE", "CLOSED", "NOTICE", "AUTH" -> { }
            }
        } catch (_: Exception) { }
    }

    fun req(subId: String, vararg filters: JSONObject) {
        val a = JSONArray().put("REQ").put(subId)
        filters.forEach { a.put(it) }
        ws.send(a.toString())
    }

    fun closeSub(subId: String) {
        ws.send(JSONArray().put("CLOSE").put(subId).toString())
    }

    suspend fun publish(e: NostrEvent, timeoutMs: Long = 8_000): Boolean {
        if (!up) return false
        val d = CompletableDeferred<Boolean>()
        okWaiters[e.id] = d
        ws.send(JSONArray().put("EVENT").put(e.toJsonObject()).toString())
        return withTimeoutOrNull(timeoutMs) { d.await() } ?: false.also { okWaiters.remove(e.id) }
    }

    fun close() = ws.close(1000, "bye")
}

class RelayPool(private val http: OkHttpClient = defaultHttp()) {
    private val conns = ConcurrentHashMap<String, RelayConn>()
    private val subSeq = AtomicInteger(0)
    private val _upCount = MutableStateFlow(0)
    val upCount: StateFlow<Int> = _upCount
    private val listeners = ConcurrentHashMap<String, (NostrEvent) -> Unit>()

    val anyUp: Boolean get() = conns.values.any { it.up }

    fun ensure(urls: List<String>) {
        urls.forEach { u ->
            conns.computeIfAbsent(u) {
                RelayConn(u, http, onEvent = { _, e ->
                    listeners.values.forEach { runCatching { it(e) } }
                }, onState = {
                    _upCount.value = conns.values.count { c -> c.up }
                })
            }
        }
    }

    fun onEvent(key: String, handler: (NostrEvent) -> Unit) {
        listeners[key] = handler
    }

    fun subscribeFeed() {
        val subId = "feed${subSeq.incrementAndGet()}"
        val since = System.currentTimeMillis() / 1000 - 86_400
        val filter = JSONObject()
            .put("kinds", JSONArray(listOf(0, 1, 6, 7, 9735, 1059)))
            .put("since", since)
            .put("limit", 200)
        conns.values.forEach { it.req(subId, filter) }
    }

    fun subscribePubkeys(pubkeys: List<String>) {
        if (pubkeys.isEmpty()) return
        val subId = "p${subSeq.incrementAndGet()}"
        val filter = JSONObject()
            .put("authors", JSONArray(pubkeys.take(50)))
            .put("kinds", JSONArray(listOf(0, 1, 6, 7)))
            .put("limit", 100)
        conns.values.forEach { it.req(subId, filter) }
        val wraps = JSONObject()
            .put("kinds", JSONArray(listOf(1059)))
            .put("#p", JSONArray(pubkeys.take(1)))
            .put("limit", 100)
        conns.values.forEach { it.req("dm${subSeq.incrementAndGet()}", wraps) }
    }

    suspend fun publish(e: NostrEvent, writeRelays: List<String>): Map<String, Boolean> {
        ensure(writeRelays)
        return writeRelays.associateWith { u -> conns[u]?.publish(e) ?: false }
    }

    fun close() = conns.values.forEach { it.close() }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
