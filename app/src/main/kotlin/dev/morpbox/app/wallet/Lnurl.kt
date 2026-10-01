// SPDX-License-Identifier: MIT
package dev.morpbox.app.wallet

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class LnurlPayParams(
    val callback: String,
    val minMsat: Long,
    val maxMsat: Long,
    val commentAllowed: Int,
    val nostrPubkey: String?,
    val allowsNostr: Boolean,
)

class LnurlClient(private val http: OkHttpClient = OkHttpClient()) {

    fun lud16ToUrl(lud16: String): String {
        val parts = lud16.trim().split("@")
        require(parts.size == 2) { "lud16 looks like name@domain" }
        return "https://${parts[1]}/.well-known/lnurlp/${parts[0]}"
    }

    fun fetchPayParams(lud16: String): LnurlPayParams {
        val url = lud16ToUrl(lud16)
        val body = get(url)
        val o = JSONObject(body)
        require(o.optString("tag") == "payRequest" || o.has("callback")) { "not a lnurl-pay endpoint" }
        return LnurlPayParams(
            callback = o.getString("callback"),
            minMsat = o.optLong("minSendable", 1_000),
            maxMsat = o.optLong("maxSendable", 100_000_000),
            commentAllowed = o.optInt("commentAllowed", 0),
            nostrPubkey = o.optString("nostrPubkey").ifBlank { null },
            allowsNostr = o.optBoolean("allowsNostr", false),
        )
    }

    fun requestInvoice(
        params: LnurlPayParams,
        amountMsat: Long,
        zapRequestJson: String? = null,
        comment: String? = null,
    ): String {
        val cb = params.callback.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("bad callback")
        cb.addQueryParameter("amount", amountMsat.toString())
        if (!zapRequestJson.isNullOrBlank() && params.allowsNostr) {
            cb.addQueryParameter("nostr", zapRequestJson)
        }
        if (!comment.isNullOrBlank() && params.commentAllowed > 0) {
            cb.addQueryParameter("comment", comment.take(params.commentAllowed))
        }
        val o = JSONObject(get(cb.build().toString()))
        val invoice = o.optString("pr")
        require(invoice.startsWith("ln", ignoreCase = true)) { "lnurl did not return a bolt11" }
        return invoice
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).get().header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            require(r.isSuccessful) { "HTTP ${r.code} $url" }
            return body
        }
    }

    companion object {
        fun encodeQuery(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
