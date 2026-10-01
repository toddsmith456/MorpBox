// SPDX-License-Identifier: MIT
package dev.morpbox.app.protocol

import dev.morpbox.app.crypto.Bech32
import dev.morpbox.app.crypto.sha256

object MorpKinds {
    const val META = 0
    const val TEXT_NOTE = 1
    const val CONTACTS = 3
    const val REPOST = 6
    const val REACTION = 7
    const val SEAL = 13
    const val CHAT_RUMOR = 14
    const val FILE_RUMOR = 15
    const val GIFT_WRAP = 1059
    const val GEO_ROOM = 20000
    const val NAMED_ROOM = 23333
    const val ZAP_REQUEST = 9734
    const val ZAP_RECEIPT = 9735
    const val RELAY_LIST = 10002
    const val BLOSSOM_SERVERS = 10063
    const val MORP_IDENTITY = 10080
    const val MORP_PAYMENT = 10081
    const val MORP_MEDIA_DESC = 10082
    const val MORP_DELIVERY = 10083
    const val XMR_PAYTO = 10133
    const val BLOSSOM_AUTH = 24242
    const val GROUP_MEMBER_CAP = 12

    fun isReplaceable(kind: Int): Boolean = kind == 0 || kind == 3 || kind in 10000..19999
    fun isEphemeral(kind: Int): Boolean = kind in 20000..29999
}

object GroupHandle {
    fun of(groupSecret: ByteArray): String = Bech32.encode("morpgrp", sha256(groupSecret).copyOf(20))
}

object PaytoTargets {
    fun parse(tags: List<List<String>>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (t in tags) {
            if (t.size >= 3 && t[0] == "payto") when (t[1]) {
                "monero", "xmr" -> out["xmr"] = t[2]
                "lightning", "ln", "bolt11" -> out["ln"] = t[2]
            }
        }
        return out
    }
}

object MorpOp {
    const val NOSTR_PUBLISH = "nostr-publish"
    const val DM_DELIVER = "dm-deliver"
    const val GROUP_DELIVER = "group-deliver"
    const val MEDIA_UPLOAD = "media-upload"
    const val LN_SEND = "ln-send"
    const val XMR_BCAST = "xmr-bcast"
    const val MESH_DELIVER = "mesh-deliver"
}

object PortalTier {
    const val P1 = "p1"
    const val P2 = "p2"
    const val P3 = "p3"
    const val MAILBOX = "mailbox"
    const val RELAY = "relay"
    const val MASK_P1 = 0x01
    const val MASK_P2 = 0x02
    const val MASK_P3 = 0x04
    const val MASK_MAILBOX = 0x08
    const val MASK_RELAY = 0x10

    fun maskOf(caps: List<String>): Int {
        var m = 0
        if (P1 in caps) m = m or MASK_P1
        if (P2 in caps) m = m or MASK_P2
        if (P3 in caps) m = m or MASK_P3
        if (MAILBOX in caps) m = m or MASK_MAILBOX
        if (RELAY in caps) m = m or MASK_RELAY
        return m
    }

    fun capsOf(mask: Int): List<String> = buildList {
        if (mask and MASK_P1 != 0) add(P1)
        if (mask and MASK_P2 != 0) add(P2)
        if (mask and MASK_P3 != 0) add(P3)
        if (mask and MASK_MAILBOX != 0) add(MAILBOX)
        if (mask and MASK_RELAY != 0) add(RELAY)
    }
}
