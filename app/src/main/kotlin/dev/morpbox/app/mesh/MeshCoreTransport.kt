// SPDX-License-Identifier: MIT
package dev.morpbox.app.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import dev.morpbox.app.protocol.FrameReassembler
import dev.morpbox.app.protocol.MorpFrame
import dev.morpbox.app.protocol.MorpNerd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * Phone → MeshCore LoRa companion over Nordic UART.
 *
 * After APP_START, MORP payloads ride as channel data datagrams (cmd 0x3E) on
 * channel 0 with data_type 0xFFFF (developer namespace) and a "MORP" magic
 * prefix so stock MeshCore apps ignore them as opaque blobs.
 *
 * Spec: https://docs.meshcore.io/companion_protocol/
 */
class MeshCoreTransport(
    private val ctx: Context,
    private val onPayload: (ByteArray) -> Unit,
) : MorpTransport {
    override val name = "meshcore"
    override val mtu = MorpNerd.MTU_BLE_MESHCORE

    private val _status = MutableStateFlow(LinkStatus("meshcore", LinkState.DOWN, detail = "no radio"))
    override val status: StateFlow<LinkStatus> = _status

    private val mgr = ctx.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var enabled = false
    private val writes = LinkedBlockingQueue<ByteArray>()
    private var writing = false
    private val reasm = FrameReassembler()
    @Volatile var selfName: String = ""
    @Volatile var batteryMv: Int = 0

    companion object {
        val NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // app → firmware
        val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // firmware → app
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val CMD_APP_START: Byte = 0x01
        const val CMD_DEVICE_QUERY: Byte = 0x16
        const val CMD_SEND_CHANNEL_DATA: Byte = 0x3E
        const val CMD_SYNC_NEXT: Byte = 0x0A
        const val CMD_BATTERY: Byte = 0x14
        const val RESP_CHANNEL_DATA: Int = 0x1B
        const val RESP_SELF_INFO: Int = 0x05
        const val RESP_BATTERY: Int = 0x0C
        const val RESP_MSG_WAITING: Int = 0x83
        const val DATA_TYPE_DEV: Int = 0xFFFF
        val MAGIC = byteArrayOf(0x4D, 0x4F, 0x52, 0x50) // "MORP"
    }

    @SuppressLint("MissingPermission")
    override suspend fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) startScan() else stop()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = mgr.adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCb)
        _status.value = LinkStatus("meshcore", LinkState.DEGRADED, detail = "scanning for radio")
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(type: Int, result: ScanResult) {
            val name = result.device.name ?: ""
            if (name.contains("MeshCore", ignoreCase = true) || result.scanRecord?.serviceUuids?.any { it.uuid == NUS } == true) {
                runCatching { mgr.adapter?.bluetoothLeScanner?.stopScan(this) }
                connect(result.device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) {
        _status.value = LinkStatus("meshcore", LinkState.DEGRADED, 0, "connecting ${dev.address}")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _status.value = LinkStatus("meshcore", LinkState.DOWN, detail = "radio lost")
                if (enabled) startScan()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(NUS) ?: return
            rx = svc.getCharacteristic(NUS_RX)
            tx = svc.getCharacteristic(NUS_TX)
            tx?.let {
                g.setCharacteristicNotification(it, true)
                it.getDescriptor(CCCD)?.let { d ->
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
            g.requestMtu(512)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            enqueue(byteArrayOf(CMD_APP_START) + ByteArray(7) + "MorpBox".toByteArray())
            enqueue(byteArrayOf(CMD_DEVICE_QUERY, 0x03))
            enqueue(byteArrayOf(CMD_BATTERY))
            _status.value = LinkStatus("meshcore", LinkState.UP, 1, "radio ready")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
            if (char.uuid == NUS_TX) handleNotify(char.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
            if (char.uuid == NUS_TX) handleNotify(value)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            writing = false
            drain()
        }
    }

    private fun handleNotify(frame: ByteArray) {
        if (frame.isEmpty()) return
        when (frame[0].toInt() and 0xFF) {
            RESP_SELF_INFO -> {
                selfName = frame.drop(1).toByteArray().toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                _status.value = _status.value.copy(detail = selfName.ifBlank { "radio ready" })
            }
            RESP_BATTERY -> if (frame.size >= 3) {
                batteryMv = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)
            }
            RESP_MSG_WAITING -> enqueue(byteArrayOf(CMD_SYNC_NEXT))
            RESP_CHANNEL_DATA -> parseDatagram(frame)
        }
    }

    private fun parseDatagram(frame: ByteArray) {
        // 0x1B | snr | res | res | ch | path_len | type_le | len | payload
        if (frame.size < 9) return
        val type = (frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8)
        val len = frame[8].toInt() and 0xFF
        if (9 + len > frame.size) return
        val payload = frame.copyOfRange(9, 9 + len)
        if (type != DATA_TYPE_DEV && type != 0x4D42) return
        val body = if (payload.size >= 4 && payload.copyOfRange(0, 4).contentEquals(MAGIC)) {
            payload.copyOfRange(4, payload.size)
        } else payload
        try {
            val (_, complete) = reasm.feed(body)
            if (complete != null) onPayload(complete)
        } catch (_: Exception) {
            onPayload(body)
        }
    }

    fun sendMorpPayload(packet: ByteArray) {
        val frames = MorpFrame.frame(packet, mtu = 140, ftype = MorpFrame.F_DATA)
        frames.forEach { f ->
            val blob = MAGIC + f
            // cmd 0x3E | ch 0 | path_len 0xFF (flood) | type 0xFFFF | payload
            val buf = ByteBuffer.allocate(4 + blob.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(CMD_SEND_CHANNEL_DATA)
            buf.put(0x00) // channel 0 public
            buf.put(0xFF.toByte()) // flood
            buf.putShort(DATA_TYPE_DEV.toShort())
            buf.put(blob)
            enqueue(buf.array())
        }
    }

    override suspend fun send(nextHop: ByteArray, packet: ByteArray) = sendMorpPayload(packet)

    @SuppressLint("MissingPermission")
    private fun enqueue(cmd: ByteArray) {
        writes.offer(cmd)
        drain()
    }

    @SuppressLint("MissingPermission")
    private fun drain() {
        if (writing) return
        val g = gatt ?: return
        val c = rx ?: return
        val next = writes.poll() ?: return
        writing = true
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = next
        if (!g.writeCharacteristic(c)) writing = false
    }

    @SuppressLint("MissingPermission")
    private fun stop() {
        runCatching { mgr.adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        runCatching { gatt?.close() }
        gatt = null
        rx = null
        tx = null
        _status.value = LinkStatus("meshcore", LinkState.DOWN, detail = "off")
    }
}
