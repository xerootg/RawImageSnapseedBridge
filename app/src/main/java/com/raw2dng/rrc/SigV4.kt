package com.raw2dng.rrc

import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 for S3 (header auth, unsigned payload). Enough for
 * what a read-only RapidRawCloud client needs: GET / HEAD with query strings
 * and `Range`. Written against the AWS reference algorithm; no SDK, because
 * the AWS SDK for Android is ~10 MB and this is 120 lines.
 */
object SigV4 {
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    data class Credentials(val accessKey: String, val secretKey: String)

    /**
     * Returns the headers to send: the caller's [headers] plus `x-amz-date`,
     * `x-amz-content-sha256` and `Authorization`. [headers] keys are
     * case-insensitive; `Host` is derived from [url].
     */
    fun sign(
        method: String,
        url: URL,
        headers: Map<String, String>,
        region: String,
        creds: Credentials,
        service: String = "s3",
        now: Date = Date()
    ): Map<String, String> {
        val amzDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val dateStamp = amzDate.substring(0, 8)

        val all = LinkedHashMap<String, String>()
        headers.forEach { (k, v) -> all[k.lowercase(Locale.ROOT)] = v.trim() }
        all["host"] = hostHeader(url)
        all["x-amz-date"] = amzDate
        all["x-amz-content-sha256"] = UNSIGNED_PAYLOAD

        val signedHeaderNames = all.keys.sorted()
        val canonicalHeaders = signedHeaderNames.joinToString("") { "$it:${collapse(all[it]!!)}\n" }
        val signedHeaders = signedHeaderNames.joinToString(";")
        val canonicalUri = canonicalPath(url.path)
        val canonicalQuery = canonicalQuery(url.query)
        val canonicalRequest = listOf(method, canonicalUri, canonicalQuery, canonicalHeaders, signedHeaders, UNSIGNED_PAYLOAD).joinToString("\n")

        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(ALGORITHM, amzDate, scope, hex(sha256(canonicalRequest.toByteArray()))).joinToString("\n")

        var key = hmac("AWS4${creds.secretKey}".toByteArray(), dateStamp)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = hex(hmac(key, stringToSign))

        val out = LinkedHashMap<String, String>()
        headers.forEach { (k, v) -> out[k] = v }
        out["x-amz-date"] = amzDate
        out["x-amz-content-sha256"] = UNSIGNED_PAYLOAD
        out["Authorization"] = "$ALGORITHM Credential=${creds.accessKey}/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        return out
    }

    private fun hostHeader(url: URL): String {
        val defaultPort = url.port == -1 || url.port == url.defaultPort
        return if (defaultPort) url.host else "${url.host}:${url.port}"
    }

    /** Each path segment URI-encoded once (S3 semantics: no double encoding). */
    internal fun canonicalPath(path: String?): String {
        if (path.isNullOrEmpty()) return "/"
        return path.split('/').joinToString("/") { uriEncode(percentDecode(it), encodeSlash = true) }
    }

    internal fun canonicalQuery(query: String?): String {
        if (query.isNullOrEmpty()) return ""
        return query.split('&').filter { it.isNotEmpty() }.map { kv ->
            val i = kv.indexOf('=')
            val k = if (i < 0) kv else kv.substring(0, i)
            val v = if (i < 0) "" else kv.substring(i + 1)
            uriEncode(percentDecode(k), true) to uriEncode(percentDecode(v), true)
        }.sortedWith(compareBy({ it.first }, { it.second })).joinToString("&") { "${it.first}=${it.second}" }
    }

    /** AWS "UriEncode": unreserved characters pass, everything else is %XX of its UTF-8 bytes. */
    fun uriEncode(s: String, encodeSlash: Boolean): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~' || (ch == '/' && !encodeSlash)) {
                sb.append(ch)
            } else {
                sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    private fun percentDecode(s: String): String = try {
        java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) { s }

    private fun collapse(v: String) = v.trim().replace(Regex("\\s+"), " ")

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
