// SPDX-License-Identifier: MIT
package dev.morpbox.app.protocol

import dev.morpbox.app.crypto.sha256
import dev.morpbox.app.crypto.toHex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap

/** MORP-PKT v1 frames — mirrors protocol/morp/frame.py. */
object MorpFrame {
    val MAGIC = byteArrayOf(0x4D, 0x4F, 0x52, 0x50)
    const val VERSION: Byte = 0x01
    const val HEADER_LEN = 90

    const val F_ADV = 0x01
    const val F_PRQ = 0x02
    const val F_PRA = 0x03
    const val F_DATA = 0x04
    const val F_ACK = 0x05
    const val F_CTRL = 0x06

    const val FLAG_CHUNKED = 0x01
    const val FLAG_ACK_REQ = 0x02
    const val FLAG_PRIORITY = 0x04
    const val FLAG_PORTAL_BOUND = 0x08
    const val FLAG_FIRST = 0x10
    const val FLAG_LAST = 0x20

    val FLOOD_NEXT = ByteArray(32)
    const val TTL_DEFAULT = 8

    fun msgId(payload: ByteArray): ByteArray = sha256(payload)

    fun frame(
        payload: ByteArray,
        mtu: Int,
        nextHop: ByteArray = FLOOD_NEXT,
        ftype: Int = F_DATA,
        ttl: Int = TTL_DEFAULT,
        baseFlags: Int = 0,
    ): List<ByteArray> {
        require(nextHop.size == 32)
        require(mtu > HEADER_LEN + 1)
        val id = msgId(payload)
        val pathId = id.copyOf(8)
        val chunkSize = mtu - HEADER_LEN
        val chunks = payload.toList().chunked(chunkSize).map { it.toByteArray() }.ifEmpty { listOf(ByteArray(0)) }
        return chunks.mapIndexed { seq, ch ->
            var flags = baseFlags
            if (chunks.size > 1) flags = flags or FLAG_CHUNKED
            if (seq == 0) flags = flags or FLAG_FIRST
            if (seq == chunks.size - 1) flags = flags or FLAG_LAST
            val head = ByteBuffer.allocate(88).order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC).put(VERSION).put(ftype.toByte()).put(nextHop).put(pathId)
                .put(ttl.toByte()).put(flags.toByte()).put(id)
                .putShort(seq.toShort()).putShort(chunks.size.toShort())
                .putShort(ch.size.toShort()).putShort((HEADER_LEN + ch.size).toShort()).array()
            val crc = crc16(head.copyOfRange(4, head.size) + ch)
            head + ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(crc.toShort()).array() + ch
        }
    }

    data class Frame(
        val ftype: Int,
        val nextHop: ByteArray,
        val pathId: ByteArray,
        val ttl: Int,
        val flags: Int,
        val msgId: ByteArray,
        val seq: Int,
        val total: Int,
        val chunk: ByteArray,
    ) {
        val isFlood: Boolean get() = nextHop.contentEquals(FLOOD_NEXT)
    }

    fun parse(raw: ByteArray): Frame {
        require(raw.size >= HEADER_LEN && raw[0] == 0x4D.toByte() && raw[1] == 0x4F.toByte() &&
            raw[2] == 0x52.toByte() && raw[3] == 0x50.toByte())
        require(raw[4] == VERSION)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        buf.position(5)
        val ftype = buf.get().toInt() and 0xFF
        val nextHop = ByteArray(32).also { buf.get(it) }
        val pathId = ByteArray(8).also { buf.get(it) }
        val ttl = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF
        val msgId = ByteArray(32).also { buf.get(it) }
        val seq = buf.short.toInt() and 0xFFFF
        val total = buf.short.toInt() and 0xFFFF
        val chunkLen = buf.short.toInt() and 0xFFFF
        val pktLen = buf.short.toInt() and 0xFFFF
        val want = buf.short.toInt() and 0xFFFF
        val chunk = raw.copyOfRange(HEADER_LEN, HEADER_LEN + chunkLen)
        require(raw.size >= pktLen && chunk.size == chunkLen) { "truncated frame" }
        require(crc16(raw.copyOfRange(4, 88) + chunk) == want) { "CRC mismatch" }
        require(seq < total) { "seq out of range" }
        return Frame(ftype, nextHop, pathId, ttl, flags, msgId, seq, total, chunk)
    }

    fun crc16(data: ByteArray, crc: Int = 0xFFFF): Int {
        var c = crc
        for (b in data) {
            c = c xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF
            }
        }
        return c
    }
}

class FrameReassembler(private val ttlMs: Long = 600_000, private val maxMsgs: Int = 256) {
    private data class Entry(var t: Long, val total: Int, val got: MutableMap<Int, ByteArray>)
    private val msgs = LinkedHashMap<String, Entry>()

    @Synchronized
    fun feed(raw: ByteArray): Pair<MorpFrame.Frame, ByteArray?> {
        gc()
        val f = MorpFrame.parse(raw)
        val key = f.msgId.toHex()
        val e = msgs.getOrPut(key) { Entry(System.currentTimeMillis(), f.total, mutableMapOf()) }
        require(e.total == f.total) { "conflicting total" }
        e.got[f.seq] = f.chunk
        e.t = System.currentTimeMillis()
        if (e.got.size == e.total) {
            msgs.remove(key)
            val data = (0 until e.total).flatMap { e.got[it]!!.asIterable() }.toByteArray()
            require(sha256(data).contentEquals(f.msgId)) { "payload hash != msg_id" }
            return f to data
        }
        return f to null
    }

    private fun gc() {
        val now = System.currentTimeMillis()
        msgs.keys.toList().filter { now - msgs[it]!!.t > ttlMs }.forEach { msgs.remove(it) }
        while (msgs.size > maxMsgs) msgs.remove(msgs.keys.first())
    }
}
