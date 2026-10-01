// SPDX-License-Identifier: MIT
package dev.morpbox.app

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.morpbox.app.crypto.IdentityStore
import dev.morpbox.app.crypto.hexToBytes
import dev.morpbox.app.crypto.toHex
import dev.morpbox.app.data.LocalStore
import dev.morpbox.app.mesh.BleMeshTransport
import dev.morpbox.app.mesh.InternetTransport
import dev.morpbox.app.mesh.LinkState
import dev.morpbox.app.mesh.MeshCoreTransport
import dev.morpbox.app.mesh.MeshEnvelope
import dev.morpbox.app.mesh.MeshMessage
import dev.morpbox.app.mesh.MeshService
import dev.morpbox.app.mesh.TransportManager
import dev.morpbox.app.nostr.EmbeddedRelay
import dev.morpbox.app.nostr.Nip17
import dev.morpbox.app.nostr.RelayPool
import dev.morpbox.app.protocol.MorpKinds
import dev.morpbox.app.protocol.MorpNerd
import dev.morpbox.app.protocol.MorpOp
import dev.morpbox.app.protocol.NostrBuilders
import dev.morpbox.app.protocol.NostrEvent
import dev.morpbox.app.protocol.PortalRegistry
import dev.morpbox.app.protocol.PortalTier
import dev.morpbox.app.wallet.LnurlClient
import dev.morpbox.app.wallet.NwcClient
import dev.morpbox.app.wallet.PayResult
import dev.morpbox.app.wallet.PaymentInstruction
import dev.morpbox.app.wallet.ZapService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MorpSession(
    private val app: Context,
    val identity: IdentityStore,
    val store: LocalStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keys = identity.keys
    val pubkey: String = keys.pubkeyHex
    val npub: String = keys.npub

    val relays = RelayPool()
    val embedded = EmbeddedRelay()
    val portals = PortalRegistry()
    val transports = TransportManager()
    private val lnurl = LnurlClient()
    private val nwc = NwcClient(relays)

    private val hop = MorpNerd.hopId(keys.pubkeyHex.hexToBytes())
    private val ble = BleMeshTransport(app, keys.pubkeyHex.hexToBytes(), hop, ::onMeshBytes)
    private val meshcore = MeshCoreTransport(app, ::onMeshBytes)
    private val internet = InternetTransport(app)

    private val _feed = MutableStateFlow<List<FeedItem>>(emptyList())
    val feed: StateFlow<List<FeedItem>> = _feed

    private val _rooms = MutableStateFlow<List<LocalStore.StoredRoom>>(emptyList())
    val rooms: StateFlow<List<LocalStore.StoredRoom>> = _rooms

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts = _toast.asSharedFlow()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val _outbox = MutableStateFlow<List<LocalStore.OutboxItem>>(emptyList())
    val outbox: StateFlow<List<LocalStore.OutboxItem>> = _outbox

    private var beaconJob: Job? = null
    private var drainJob: Job? = null

    data class FeedItem(val event: NostrEvent, val via: String, val authorName: String)
    data class Status(
        val internet: String = "offline",
        val blePeers: Int = 0,
        val meshcore: String = "off",
        val relaysUp: Int = 0,
        val portals: Int = 0,
        val meshOn: Boolean = false,
        val portal: Boolean = true,
        val policy: String = "auto",
    )

    fun start() {
        transports.register(internet)
        transports.register(ble)
        transports.register(meshcore)
        transports.routePolicy = identity.routePolicy
        relays.ensure(identity.relays)
        relays.onEvent("main", ::onRelayEvent)
        relays.subscribeFeed()
        relays.subscribePubkeys(listOf(pubkey))
        refreshFeed()
        refreshRooms()
        refreshOutbox()
        refreshStatus()
        drainJob = scope.launch {
            while (isActive) {
                drainOutbox()
                refreshStatus()
                delay(8_000)
            }
        }
        if (identity.meshOn) scope.launch { setMesh(true) }
    }

    fun onMeshServiceStarted() {
        scope.launch { ble.setEnabled(true); meshcore.setEnabled(true); startBeacons() }
    }

    fun onMeshServiceStopped() {
        scope.launch { ble.setEnabled(false); meshcore.setEnabled(false) }
        beaconJob?.cancel()
    }

    fun setMesh(on: Boolean) {
        identity.meshOn = on
        val i = Intent(app, MeshService::class.java).putExtra(MeshService.EXTRA_ON, on)
        if (on) {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } else {
            app.stopService(i)
            scope.launch {
                ble.setEnabled(false)
                meshcore.setEnabled(false)
            }
        }
        refreshStatus()
    }

    fun setPolicy(p: String) {
        identity.routePolicy = p
        transports.routePolicy = p
        refreshStatus()
    }

    fun setPortal(on: Boolean) {
        identity.actAsPortal = on
        refreshStatus()
    }

    private fun startBeacons() {
        beaconJob?.cancel()
        beaconJob = scope.launch {
            while (isActive) {
                val caps = buildList {
                    if (internet.status.value.state == LinkState.UP) {
                        add(PortalTier.P1); add(PortalTier.P2); add(PortalTier.P3); add(PortalTier.RELAY)
                    }
                    add(PortalTier.MAILBOX)
                }
                ble.capsMask = PortalTier.maskOf(caps)
                val msg = MeshMessage.Beacon(
                    hopHex = hop.toHex(),
                    devPubHex = pubkey,
                    npub = npub,
                    caps = caps,
                    load = 0,
                    relays = identity.relays,
                    name = identity.displayName.ifBlank { npub.take(12) },
                )
                runCatching { transports.flood(MeshEnvelope.encode(msg)) }
                delay(15_000)
            }
        }
    }

    private fun onRelayEvent(e: NostrEvent) {
        ingest(e, "internet")
        if (identity.actAsPortal && identity.meshOn && e.kind in setOf(MorpKinds.TEXT_NOTE, MorpKinds.REPOST, MorpKinds.GIFT_WRAP)) {
            scope.launch { runCatching { transports.flood(MeshEnvelope.encode(MeshMessage.Event(e))) } }
        }
    }

    private fun onMeshBytes(bytes: ByteArray) {
        val msg = MeshEnvelope.decode(bytes) ?: return
        when (msg) {
            is MeshMessage.Event -> {
                ingest(msg.event, "mesh")
                if (identity.actAsPortal && internet.status.value.state == LinkState.UP &&
                    msg.event.kind in setOf(MorpKinds.TEXT_NOTE, MorpKinds.REPOST, MorpKinds.GIFT_WRAP, MorpKinds.SEAL)
                ) {
                    scope.launch { relays.publish(msg.event, identity.relays) }
                }
            }
            is MeshMessage.Beacon -> {
                portals.addBeacon(
                    hop = msg.hopHex.hexToBytes(),
                    devPub = msg.devPubHex.hexToBytes(),
                    relays = msg.relays,
                    caps = msg.caps,
                    load = msg.load,
                    transport = "ble",
                )
                if (msg.npub.isNotBlank()) store.upsertContact(msg.devPubHex, msg.name)
                refreshStatus()
            }
            is MeshMessage.Receipt -> { }
        }
    }

    private fun ingest(e: NostrEvent, via: String) {
        if (!e.verify() && e.kind != MorpKinds.CHAT_RUMOR) return
        embedded.store(e)
        store.putEvent(e, via)
        when (e.kind) {
            MorpKinds.TEXT_NOTE, MorpKinds.REPOST, MorpKinds.REACTION, MorpKinds.META -> refreshFeed()
            MorpKinds.GIFT_WRAP -> openWrap(e, via)
            MorpKinds.NAMED_ROOM, MorpKinds.GEO_ROOM -> ingestChannel(e, via)
            MorpKinds.ZAP_RECEIPT -> refreshFeed()
        }
    }

    private fun ingestChannel(e: NostrEvent, via: String) {
        val room = e.firstTag("d") ?: identity.namedRoom
        store.putMessage(
            LocalStore.StoredMessage(e.id, "ch:$room", e.pubkey, e.createdAt * 1000, e.content, via, false),
        )
        refreshRooms()
    }

    private fun openWrap(e: NostrEvent, via: String) {
        val rumor = Nip17.open(keys.secret, pubkey, e) ?: return
        val roomId = rumor.tags.firstOrNull { it.getOrNull(0) == "d" }?.getOrNull(1)
            ?: "dm:${rumor.pubkey}"
        store.upsertContact(rumor.pubkey)
        store.upsertRoom(
            LocalStore.StoredRoom(
                id = roomId,
                kind = if (roomId.startsWith("g:") || roomId.startsWith("ch:")) "group" else "dm",
                title = store.displayName(rumor.pubkey),
                members = listOf(pubkey, rumor.pubkey),
                lastTs = rumor.createdAt * 1000,
                lastPreview = rumor.content.take(80),
            ),
        )
        store.putMessage(
            LocalStore.StoredMessage(
                id = e.id, roomId = roomId, sender = rumor.pubkey,
                ts = rumor.createdAt * 1000, text = rumor.content, via = via, outbound = false,
            ),
        )
        refreshRooms()
    }

    fun refreshFeed() {
        _feed.value = store.feed(200).map {
            FeedItem(it.event, it.via, store.displayName(it.event.pubkey))
        }
    }

    fun refreshRooms() { _rooms.value = store.allRooms() }
    fun refreshOutbox() { _outbox.value = store.allOutbox() }

    fun refreshStatus() {
        val inet = internet.status.value
        _status.value = Status(
            internet = inet.detail.ifBlank { inet.state.name.lowercase() },
            blePeers = ble.peerCount(),
            meshcore = meshcore.status.value.detail.ifBlank { meshcore.status.value.state.name.lowercase() },
            relaysUp = relays.upCount.value,
            portals = portals.portalCount(),
            meshOn = identity.meshOn,
            portal = identity.actAsPortal,
            policy = identity.routePolicy,
        )
    }

    suspend fun post(text: String, replyTo: NostrEvent? = null): Result<NostrEvent> = runCatching {
        val unsigned = if (replyTo != null) NostrBuilders.reply(pubkey, text, replyTo) else NostrBuilders.kind1(pubkey, text)
        val signed = unsigned.signed(keys.secret)
        dispatchSigned(signed, MorpOp.NOSTR_PUBLISH)
        signed
    }

    suspend fun react(target: NostrEvent, content: String = "+") = runCatching {
        dispatchSigned(NostrBuilders.reaction(pubkey, target, content).signed(keys.secret), MorpOp.NOSTR_PUBLISH)
    }

    suspend fun publishProfile() = runCatching {
        val e = NostrBuilders.metadata(pubkey, identity.displayName, identity.about, identity.lud16, identity.xmrAddress)
            .signed(keys.secret)
        dispatchSigned(e, MorpOp.NOSTR_PUBLISH)
    }

    suspend fun sendDm(peerHex: String, text: String, room: String = ""): Result<Unit> = runCatching {
        store.upsertContact(peerHex)
        val roomId = room.ifBlank { "dm:$peerHex" }
        val rumor = Nip17.rumor(pubkey, text, peerHex, room)
        val wrap = Nip17.wrap(keys.secret, pubkey, peerHex, rumor)
        store.putMessage(
            LocalStore.StoredMessage(wrap.id, roomId, pubkey, System.currentTimeMillis(), text, "local", true),
        )
        store.upsertRoom(
            LocalStore.StoredRoom(roomId, if (room.startsWith("g:")) "group" else "dm", store.displayName(peerHex), listOf(pubkey, peerHex), System.currentTimeMillis(), text),
        )
        refreshRooms()
        dispatchSigned(wrap, MorpOp.DM_DELIVER)
    }

    suspend fun sendGroup(memberHexes: List<String>, text: String, room: String): Result<Unit> = runCatching {
        require(memberHexes.size <= MorpKinds.GROUP_MEMBER_CAP)
        val wraps = Nip17.wrapGroup(keys.secret, pubkey, memberHexes, text, room)
        wraps.forEach { dispatchSigned(it, MorpOp.GROUP_DELIVER) }
        store.putMessage(
            LocalStore.StoredMessage(wraps.first().id, room, pubkey, System.currentTimeMillis(), text, "local", true),
        )
        refreshRooms()
    }

    suspend fun sendChannel(text: String): Result<NostrEvent> = runCatching {
        val room = identity.namedRoom
        val e = NostrEvent(
            pubkey, NostrBuilders.now(), MorpKinds.NAMED_ROOM,
            listOf(listOf("d", room)), text,
        ).signed(keys.secret)
        dispatchSigned(e, MorpOp.NOSTR_PUBLISH)
        e
    }

    private suspend fun dispatchSigned(signed: NostrEvent, op: String) {
        ingest(signed, "local")
        val item = LocalStore.OutboxItem(
            id = signed.id, op = op, signedJson = signed.toJson(),
            relays = identity.relays, needCaps = listOf(PortalTier.P1),
            state = LocalStore.Delivery.QUEUED, retries = 0, updatedAt = System.currentTimeMillis(),
        )
        store.enqueue(item)
        refreshOutbox()
        drainOne(item)
    }

    private suspend fun drainOutbox() {
        store.pendingOutbox().forEach { drainOne(it) }
        refreshOutbox()
    }

    private suspend fun drainOne(item: LocalStore.OutboxItem) {
        val event = try {
            NostrEvent.fromJson(item.signedJson)
        } catch (_: Exception) {
            store.updateOutbox(item.id, LocalStore.Delivery.FAILED)
            return
        }
        val policy = identity.routePolicy
        val netUp = internet.status.value.state == LinkState.UP && relays.anyUp
        var published = false
        if (policy != "force-mesh" && netUp) {
            val res = relays.publish(event, identity.relays)
            published = res.values.any { it }
            if (published) store.updateOutbox(item.id, LocalStore.Delivery.PUBLISHED)
        }
        val meshWanted = policy != "force-internet" && identity.meshOn
        if (meshWanted) {
            runCatching { transports.flood(MeshEnvelope.encode(MeshMessage.Event(event))) }
            if (!published) store.updateOutbox(item.id, LocalStore.Delivery.ON_MESH)
        }
        if (!published && !meshWanted) {
            store.updateOutbox(item.id, LocalStore.Delivery.QUEUED, item.retries + 1)
        }
        if (published && meshWanted) {
            // dual-path success: internet took it, mesh extra-gossips for local island
            store.updateOutbox(item.id, LocalStore.Delivery.PUBLISHED)
        }
    }

    suspend fun zap(target: NostrEvent, amountSats: Long, comment: String = ""): PayResult =
        withContext(Dispatchers.IO) {
            val profile = store.profile(target.pubkey)
            val lud16 = profile?.lud16?.ifBlank { null }
                ?: ZapService.lud16FromKind0(store.event(target.id)?.event?.content ?: "")
            if (lud16.isNullOrBlank()) {
                return@withContext PayResult(false, message = "No lightning address on this profile")
            }
            val amountMsat = amountSats * 1_000
            val unsigned = ZapService.buildRequest(pubkey, target, amountMsat, lud16, identity.relays, comment)
            val signed = unsigned.signed(keys.secret)
            try {
                val params = lnurl.fetchPayParams(lud16)
                val invoice = lnurl.requestInvoice(params, amountMsat, signed.toJson(), comment)
                if (identity.nwcUri.isNotBlank()) {
                    val paid = nwc.payInvoice(identity.nwcUri, invoice)
                    if (paid.ok) ingest(signed, "local")
                    return@withContext paid.copy(invoice = invoice)
                }
                if (internet.status.value.state != LinkState.UP) {
                    val ix = PaymentInstruction("ln", invoice, amountMsat, memo = comment, payerNostr = pubkey)
                        .signed(keys.secret, pubkey)
                    store.enqueue(
                        LocalStore.OutboxItem(
                            ix.nonce, MorpOp.LN_SEND, ix.toUnsignedEvent().signed(keys.secret).toJson(),
                            identity.relays, listOf(PortalTier.P3), LocalStore.Delivery.QUEUED, 0, System.currentTimeMillis(),
                            extra = invoice,
                        ),
                    )
                    return@withContext PayResult(true, invoice = invoice, queued = true, message = "Queued until internet")
                }
                PayResult(true, invoice = invoice, message = "Invoice ready — pay with any Lightning wallet")
            } catch (e: Exception) {
                PayResult(false, message = e.message ?: "zap failed")
            }
        }

    suspend fun xap(payto: String, atomic: Long, memo: String): PayResult {
        val ix = PaymentInstruction("xmr", payto, amountAtomic = atomic, memo = memo, payerNostr = pubkey)
            .signed(keys.secret, pubkey)
        val ev = ix.toUnsignedEvent().signed(keys.secret)
        dispatchSigned(ev, MorpOp.XMR_BCAST)
        return PayResult(true, queued = true, message = "XMR payto queued (broadcast at next internet)")
    }

    fun history(roomId: String) = store.history(roomId)

    fun notify(msg: String) { _toast.tryEmit(msg) }
}
