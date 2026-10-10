package com.raw2dng.rrc

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class S3Exception(val status: Int, message: String) : Exception("HTTP $status: $message")

data class S3Object(val key: String, val size: Long, val lastModifiedEpochSec: Long, val etag: String)

/**
 * Minimal read-only S3 client: ListObjectsV2, GET (whole or `Range`), HEAD.
 * Path-style by default (what Garage/MinIO/B2/R2 expect, and what
 * RapidRawCloud uses); virtual-host style is a flag.
 */
class S3Client(
    endpoint: String,
    private val bucket: String,
    private val region: String,
    private val creds: SigV4.Credentials,
    private val pathStyle: Boolean = true
) {
    companion object {
        private const val TAG = "S3Client"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    private val base: URL = URL(endpoint.trim().trimEnd('/'))

    private fun urlFor(key: String, query: String = ""): URL {
        val encodedKey = SigV4.uriEncode(key, encodeSlash = false)
        val q = if (query.isEmpty()) "" else "?$query"
        return if (pathStyle) {
            URL(base, "${base.path.trimEnd('/')}/$bucket/$encodedKey$q")
        } else {
            URL("${base.protocol}://$bucket.${base.authority}${base.path.trimEnd('/')}/$encodedKey$q")
        }
    }

    private fun open(method: String, url: URL, extraHeaders: Map<String, String> = emptyMap()): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = false
        SigV4.sign(method, url, extraHeaders, region, creds).forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun check(conn: HttpURLConnection, vararg okStatuses: Int = intArrayOf(200)) {
        val status = conn.responseCode
        if (status !in okStatuses) {
            val body = try { conn.errorStream?.bufferedReader()?.readText()?.take(400) ?: "" } catch (e: Exception) { "" }
            conn.disconnect()
            throw S3Exception(status, body.ifEmpty { conn.responseMessage ?: "" })
        }
    }

    /** All objects under [prefix] (handles pagination). */
    fun list(prefix: String, maxTotal: Int = 200_000): List<S3Object> {
        val out = ArrayList<S3Object>()
        var token: String? = null
        do {
            val q = StringBuilder("list-type=2&max-keys=1000&prefix=").append(SigV4.uriEncode(prefix, true))
            if (token != null) q.append("&continuation-token=").append(SigV4.uriEncode(token, true))
            val conn = open("GET", urlFor("", q.toString()))
            check(conn)
            val page = conn.inputStream.use { parseList(it) }
            conn.disconnect()
            out.addAll(page.first)
            token = page.second
        } while (token != null && out.size < maxTotal)
        Log.d(TAG, "Listed ${out.size} keys under '$prefix'")
        return out
    }

    /** Streams the object body; the caller closes the stream (which disconnects). */
    fun get(key: String, range: LongRange? = null): InputStream {
        val headers = if (range != null) mapOf("Range" to "bytes=${range.first}-${range.last}") else emptyMap()
        val conn = open("GET", urlFor(key), headers)
        check(conn, 200, 206)
        val body = conn.inputStream
        return object : InputStream() {
            override fun read(): Int = body.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = body.read(b, off, len)
            override fun skip(n: Long): Long = body.skip(n)
            override fun available(): Int = body.available()
            override fun close() { body.close(); conn.disconnect() }
        }
    }

    fun getBytes(key: String, range: LongRange? = null): ByteArray = get(key, range).use { it.readBytes() }

    /** Null on 404. */
    fun getBytesOrNull(key: String, range: LongRange? = null): ByteArray? = try {
        getBytes(key, range)
    } catch (e: S3Exception) {
        if (e.status == 404) null else throw e
    }

    fun exists(key: String): Boolean {
        val conn = open("HEAD", urlFor(key))
        return try {
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }

    // --- XML ---------------------------------------------------------------

    private fun parseList(input: InputStream): Pair<List<S3Object>, String?> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val objects = ArrayList<S3Object>()
        var next: String? = null
        var truncated = false
        var key = ""; var size = 0L; var modified = 0L; var etag = ""
        var inContents = false
        var text = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> { text = ""; if (parser.name == "Contents") { inContents = true; key = ""; size = 0; modified = 0; etag = "" } }
                XmlPullParser.TEXT -> text += parser.text ?: ""
                XmlPullParser.END_TAG -> when (parser.name) {
                    "Key" -> if (inContents) key = text
                    "Size" -> if (inContents) size = text.trim().toLongOrNull() ?: 0L
                    "LastModified" -> if (inContents) modified = parseIso8601(text.trim())
                    "ETag" -> if (inContents) etag = text.trim().trim('"')
                    "Contents" -> { inContents = false; objects.add(S3Object(key, size, modified, etag)) }
                    "NextContinuationToken" -> next = text.trim()
                    "IsTruncated" -> truncated = text.trim().equals("true", ignoreCase = true)
                }
            }
            event = parser.next()
        }
        return objects to (if (truncated) next else null)
    }

    private fun parseIso8601(s: String): Long = try {
        val fmt = java.text.SimpleDateFormat(if (s.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        fmt.parse(s)!!.time / 1000
    } catch (e: Exception) { 0L }
}
