// SPDX-License-Identifier: MIT
package dev.morpbox.app.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dev.morpbox.app.protocol.FrameReassembler
import dev.morpbox.app.protocol.MorpFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Bitchat-style phone-to-phone BLE mesh: advertise + scan + GATT flood with TTL,
 * dedup and chunked MORP-PKT v1 frames. Clean-room Kotlin.
 */
class BleMeshTransport(
    private val ctx: Context,
    private val myDevPub: ByteArray,
    private val myHop: ByteArray,
    private val onPayload: (ByteArray) -> Unit,
) : MorpTransport {
    override val name = "ble"
    override val mtu = 180

    private val _status = MutableStateFlow(LinkStatus("ble", LinkState.DOWN))
    override val status: StateFlow<LinkStatus> = _status

    private val mgr: BluetoothManager = ctx.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val reasm = FrameReassembler()
    private val seen = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>): Boolean = size > 2048
    }
    private val peers = ConcurrentHashMap<String, Peer>()
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var enabled = false
    var capsMask: Int = 0
    var load: Int = 0

    private data class Peer(
        val device: BluetoothDevice,
        var gatt: BluetoothGatt? = null,
        var mtu: Int = 23,
        var subscribed: Boolean = false,
        var rssi: Int = 0,
        val writes: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue(),
        var writing: Boolean = false,
    )

    companion object {
        val SERVICE: UUID = UUID.fromString("6d6f7270-6d6f-7270-624f-582d4d455448")
        val CHAR_RX: UUID = UUID.fromString("6d6f7271-6d6f-7270-624f-582d4d455448")
        val CHAR_TX: UUID = UUID.fromString("6d6f7272-6d6f-7270-624f-582d4d455448")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val SCAN_MS = 4_000L
        const val SLEEP_MS = 12_000L

        fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPerms(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    override suspend fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on && hasPerms() && mgr.adapter?.isEnabled == true) {
            startServer()
            startAdvertise()
            startScanLoop()
            _status.value = LinkStatus("ble", LinkState.UP, peers.size, "scanning")
        } else {
            stopAll()
            _status.value = LinkStatus("ble", LinkState.DOWN)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAll() {
        runCatching { scanner?.stopScan(scanCb) }
        runCatching { advertiser?.stopAdvertising(advCb) }
        peers.values.forEach { runCatching { it.gatt?.close() } }
        peers.clear()
        runCatching { gattServer?.close() }
        gattServer = null
        handler.removeCallbacksAndMessages(null)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertise() {
        val adv = mgr.adapter?.bluetoothLeAdvertiser ?: return
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE)).build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(SERVICE), myHop + byteArrayOf(capsMask.toByte(), load.toByte()))
            .build()
        adv.startAdvertising(settings, data, scan, advCb)
    }

    private val advCb = object : AdvertiseCallback() {}

    @SuppressLint("MissingPermission")
    private fun startScanLoop() {
        scanner = mgr.adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        val cycle = object : Runnable {
            override fun run() {
                if (!enabled) return
                runCatching { scanner?.startScan(listOf(filter), settings, scanCb) }
                handler.postDelayed({
                    runCatching { scanner?.stopScan(scanCb) }
                    if (enabled) handler.postDelayed(this, SLEEP_MS)
                }, SCAN_MS)
            }
        }
        handler.post(cycle)
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(type: Int, result: ScanResult) {
            val dev = result.device ?: return
            val p = peers.getOrPut(dev.address) { Peer(dev) }
            p.rssi = result.rssi
            if (p.gatt == null && enabled) {
                p.gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            }
            _status.value = LinkStatus("ble", LinkState.UP, peers.size, "peers")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServer() {
        val server = mgr.openGattServer(ctx, serverCb) ?: return
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            CHAR_RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            CHAR_TX,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        svc.addCharacteristic(rx)
        svc.addCharacteristic(tx)
        server.addService(svc)
        gattServer = server
    }

    private val serverCb = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            dev: BluetoothDevice, reqId: Int, char: BluetoothGattCharacteristic,
            prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (char.uuid == CHAR_RX) handleIncoming(value, exceptAddr = dev.address)
            if (responseNeeded) gattServer?.sendResponse(dev, reqId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onDescriptorWriteRequest(
            dev: BluetoothDevice, reqId: Int, desc: BluetoothGattDescriptor,
            prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (desc.uuid == CCCD) {
                peers.getOrPut(dev.address) { Peer(dev) }.subscribed =
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            if (responseNeeded) gattServer?.sendResponse(dev, reqId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                peers[g.device.address]?.gatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) g.requestMtu(512)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtuNow: Int, status: Int) {
            peers[g.device.address]?.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtuNow else 23
            val tx = g.getService(SERVICE)?.getCharacteristic(CHAR_TX)
            if (tx != null) {
                g.setCharacteristicNotification(tx, true)
                tx.getDescriptor(CCCD)?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(it)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
            if (char.uuid == CHAR_TX) handleIncoming(char.value ?: return, exceptAddr = g.device.address)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
            if (char.uuid == CHAR_TX) handleIncoming(value, exceptAddr = g.device.address)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            val p = peers[g.device.address] ?: return
            p.writing = false
            drainWrites(p)
        }
    }

    private fun ttlDecrement(raw: ByteArray): ByteArray? {
        if (raw.size < MorpFrame.HEADER_LEN) return null
        val copy = raw.copyOf()
        val ttl = copy[46].toInt() and 0xFF
        if (ttl <= 1) return null
        copy[46] = (ttl - 1).toByte()
        val chunkLen = ((copy[84].toInt() and 0xFF) shl 8) or (copy[85].toInt() and 0xFF)
        val chunk = copy.copyOfRange(MorpFrame.HEADER_LEN, MorpFrame.HEADER_LEN + chunkLen)
        val crc = MorpFrame.crc16(copy.copyOfRange(4, 88) + chunk)
        copy[88] = (crc ushr 8).toByte()
        copy[89] = (crc and 0xFF).toByte()
        return copy
    }

    private fun seenRecently(key: String): Boolean = synchronized(seen) {
        val now = System.currentTimeMillis()
        val hit = seen[key]?.let { now - it < 120_000 } ?: false
        if (!hit) seen[key] = now
        hit
    }

    private fun handleIncoming(raw: ByteArray, exceptAddr: String) {
        val (frame, payload) = try {
            reasm.feed(raw)
        } catch (_: Exception) {
            return
        }
        val msgHex = frame.msgId.joinToString("") { "%02x".format(it) }
        val forMe = frame.nextHop.contentEquals(myDevPub)
        if (payload != null) {
            if (forMe || frame.isFlood) onPayload(payload)
            if (!forMe) forwardRaw(raw, exceptAddr, msgHex)
            return
        }
        if (!forMe) forwardRaw(raw, exceptAddr, msgHex)
    }

    private fun forwardRaw(raw: ByteArray, exceptAddr: String, msgHex: String) {
        if (seenRecently("fwd:$msgHex:${raw.size}")) return
        val fwd = ttlDecrement(raw) ?: return
        writeAll(fwd, exceptAddr)
    }

    override suspend fun send(nextHop: ByteArray, packet: ByteArray) {
        MorpFrame.frame(packet, mtu, nextHop, MorpFrame.F_DATA, MorpFrame.TTL_DEFAULT)
            .forEach { writeAll(it, "") }
    }

    fun flood(payload: ByteArray, ftype: Int = MorpFrame.F_DATA, ttl: Int = MorpFrame.TTL_DEFAULT) {
        MorpFrame.frame(payload, mtu, MorpFrame.FLOOD_NEXT, ftype, ttl).forEach { writeAll(it, "") }
    }

    @SuppressLint("MissingPermission")
    private fun writeAll(frame: ByteArray, exceptAddr: String) {
        gattServer?.let { srv ->
            peers.forEach { (addr, p) ->
                if (addr != exceptAddr && p.subscribed) {
                    val tx = srv.getService(SERVICE)?.getCharacteristic(CHAR_TX) ?: return@forEach
                    tx.value = frame
                    runCatching { srv.notifyCharacteristicChanged(p.device, tx, false) }
                }
            }
        }
        peers.forEach { (addr, p) ->
            if (addr == exceptAddr) return@forEach
            val chunk = (p.mtu - 3).coerceAtLeast(20)
            frame.toList().chunked(chunk).forEach { part -> p.writes.offer(part.toByteArray()) }
            drainWrites(p)
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainWrites(p: Peer) {
        if (p.writing) return
        val g = p.gatt ?: return
        val rx = runCatching { g.getService(SERVICE)?.getCharacteristic(CHAR_RX) }.getOrNull() ?: return
        val next = p.writes.poll() ?: return
        p.writing = true
        try {
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            rx.value = next
            if (!g.writeCharacteristic(rx)) p.writing = false
        } catch (_: Exception) {
            p.writing = false
        }
    }

    fun peerCount(): Int = peers.size
}
