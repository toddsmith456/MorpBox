// SPDX-License-Identifier: MIT
package dev.morpbox.app.crypto

/** BIP-173 bech32 for npub / nsec / note / morpgrp. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private fun polymod(values: ByteArray): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = (chk and 0x1ffffff) shl 5 xor (v.toInt() and 0xFF)
            if (b and 1 != 0) chk = chk xor 0x3b6a57b2
            if (b and 2 != 0) chk = chk xor 0x26508e1d
            if (b and 4 != 0) chk = chk xor 0x1ea119fa
            if (b and 8 != 0) chk = chk xor 0x3d4233dd
            if (b and 16 != 0) chk = chk xor 0x2a1462b3
        }
        return chk
    }

    private fun hrpExpand(hrp: String): ByteArray {
        val out = ByteArray(hrp.length * 2 + 1)
        hrp.forEachIndexed { i, c -> out[i] = (c.code ushr 5).toByte() }
        out[hrp.length] = 0
        hrp.forEachIndexed { i, c -> out[hrp.length + 1 + i] = (c.code and 31).toByte() }
        return out
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>(data.size * from / to + 2)
        val maxv = (1 shl to) - 1
        for (b in data) {
            acc = (acc shl from) or (b.toInt() and 0xFF)
            bits += from
            while (bits >= to) {
                bits -= to
                out += ((acc ushr bits) and maxv).toByte()
            }
        }
        if (pad) {
            if (bits > 0) out += ((acc shl (to - bits)) and maxv).toByte()
        } else {
            require(bits < from && ((acc shl (to - bits)) and maxv) == 0) { "bech32 padding" }
        }
        return out.toByteArray()
    }

    fun encode(hrp: String, data: ByteArray): String {
        val five = convertBits(data, 8, 5, true)
        val values = hrpExpand(hrp) + five + ByteArray(6)
        val mod = polymod(values) xor 1
        val check = ByteArray(6) { ((mod ushr (5 * (5 - it))) and 31).toByte() }
        return hrp + "1" + (five + check).joinToString("") { CHARSET[it.toInt()].toString() }
    }

    fun decode(s: String, expectHrp: String? = null): Pair<String, ByteArray> {
        val str = s.trim().lowercase()
        val pos = str.lastIndexOf('1')
        require(pos > 0) { "no bech32 separator" }
        val hrp = str.substring(0, pos)
        if (expectHrp != null) require(hrp == expectHrp) { "want hrp $expectHrp, got $hrp" }
        val payload = str.substring(pos + 1).map {
            val i = CHARSET.indexOf(it)
            require(i >= 0) { "bad bech32 char" }
            i.toByte()
        }.toByteArray()
        require(payload.size >= 6) { "bech32 too short" }
        require(polymod(hrpExpand(hrp) + payload) == 1) { "bad bech32 checksum" }
        return hrp to convertBits(payload.copyOf(payload.size - 6), 5, 8, false)
    }

    fun npubToHex(npub: String): String = decode(npub, "npub").second.toHex()
    fun hexToNpub(hex: String): String = encode("npub", hex.hexToBytes())
    fun nsecToBytes(nsec: String): ByteArray = decode(nsec, "nsec").second
    fun bytesToNsec(sk: ByteArray): String = encode("nsec", sk)
    fun noteId(idHex: String): String = encode("note", idHex.hexToBytes())
}
