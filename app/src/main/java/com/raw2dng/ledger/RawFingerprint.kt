package com.raw2dng.ledger

import com.raw2dng.source.RawEntry
import com.raw2dng.source.RawSource
import com.raw2dng.source.SourceCapability
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Identity of a RAW file that survives being seen through different sources
 * (the same shot on the card, on the camera over PTP, and in RapidRawCloud).
 *
 * Two strategies, distinguished by prefix so they never collide:
 *
 * - `c:` **content** - SHA-256 over the file size (8 bytes LE) followed by the
 *   first [HEAD_BYTES] of the file. The head of a RAW holds the TIFF/CR3
 *   headers, maker notes and usually the thumbnail, so it is unique per
 *   capture, and it is cheap to obtain everywhere (64 KiB range read: SAF
 *   stream, PTP GetPartialObject, S3 Range GET).
 * - `m:` **metadata** - SHA-256 over source instance, name, size and capture
 *   or modification time. Only used when a source cannot read ranges (e.g. a
 *   camera without GetPartialObject). Once such a file has been transferred
 *   in full, the ledger learns its content fingerprint and links the two.
 */
object RawFingerprint {
    const val HEAD_BYTES = 64 * 1024

    fun ofContent(size: Long, head: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(longBytes(size))
        md.update(head, 0, minOf(head.size, HEAD_BYTES))
        return "c:" + hex(md.digest())
    }

    fun ofFile(file: File): String {
        val head = file.inputStream().use { readHead(it) }
        return ofContent(file.length(), head)
    }

    fun ofStream(size: Long, input: InputStream): String = ofContent(size, readHead(input))

    fun ofMetadata(entry: RawEntry): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in listOf(entry.source.encode(), entry.name, entry.size.toString(), (entry.captureEpochSec ?: entry.modifiedEpochSec).toString())) {
            md.update(part.toByteArray())
            md.update(0)
        }
        return "m:" + hex(md.digest())
    }

    /** Best available fingerprint for [entry] through [source]. */
    suspend fun compute(source: RawSource, entry: RawEntry): String {
        if (SourceCapability.PARTIAL_READ in source.capabilities && entry.size >= 0) {
            val head = source.readRange(entry, 0, HEAD_BYTES)
            if (head != null) return ofContent(entry.size, head)
        }
        return ofMetadata(entry)
    }

    fun isContent(fp: String) = fp.startsWith("c:")

    private fun readHead(input: InputStream): ByteArray {
        val buf = ByteArray(HEAD_BYTES)
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return if (read == buf.size) buf else buf.copyOf(read)
    }

    private fun longBytes(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
