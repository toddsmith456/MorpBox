// SPDX-License-Identifier: MIT
package dev.morpbox.app.protocol

/**
 * NIP-01 canonical JSON: UTF-8, no extra whitespace, unicode passed through (not \uXXXX).
 * Only `"`, `\`, and control characters are escaped.
 */
object NostrJson {
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun arrayOfStrings(row: List<String>): String =
        row.joinToString(prefix = "[", postfix = "]", separator = ",") { quote(it) }

    fun arrayOfTagRows(tags: List<List<String>>): String =
        tags.joinToString(prefix = "[", postfix = "]", separator = ",") { arrayOfStrings(it) }
}
