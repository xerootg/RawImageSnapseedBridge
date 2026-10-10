package com.raw2dng.source

import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Where RAW files come from.
 *
 * A [RawSource] is anything that can enumerate RAW objects and hand their bytes
 * to the converter: the device's own media library, a mounted USB drive (SAF
 * document tree), a camera speaking PTP/MTP over USB (later PTP/IP), or a
 * RapidRawCloud bucket. The rest of the app never talks to a storage API
 * directly; it talks to a source, and every entry is also reachable through
 * [SourceContentProvider] as a `content://` URI so the URI-based UI (previews,
 * EXIF, thumbnails, share/open-with) works for every kind of source.
 *
 * Sources are strictly read-only with respect to the remote file system: no
 * markers, sidecars or deletions ever happen on the source. What has been
 * converted is tracked locally by the conversion ledger (`com.raw2dng.ledger`).
 */
enum class SourceKind(val wire: String) {
    /** The phone's own media library (MediaStore). */
    DEVICE_MEDIA("media"),

    /** A Storage Access Framework document tree: USB mass storage, SD card, any folder. */
    DOCUMENT_TREE("tree"),

    /** A camera speaking PTP (PIMA 15740) / MTP, over any [com.raw2dng.ptp.PtpTransport]. */
    PTP("ptp"),

    /** A RapidRawCloud library bucket. */
    RRC("rrc");

    companion object {
        fun fromWire(wire: String): SourceKind? = values().firstOrNull { it.wire == wire }
    }
}

/**
 * Stable identity of a source *instance* (not a session): a camera is
 * identified by its serial number, a bucket by endpoint+name, a document tree
 * by its tree URI, the device library by a constant. Persisted in the ledger.
 */
data class SourceId(val kind: SourceKind, val instance: String) {
    fun encode(): String = "${kind.wire}:$instance"

    override fun toString(): String = encode()

    companion object {
        fun decode(encoded: String): SourceId? {
            val sep = encoded.indexOf(':')
            if (sep <= 0) return null
            val kind = SourceKind.fromWire(encoded.substring(0, sep)) ?: return null
            return SourceId(kind, encoded.substring(sep + 1))
        }
    }
}

enum class SourceCapability {
    /** [RawSource.thumbnail] returns something cheap (camera thumb, cloud thumb). */
    THUMBNAIL,

    /** [RawSource.readRange] works; used for content fingerprints without a full transfer. */
    PARTIAL_READ,

    /** Entries carry a capture timestamp distinct from the file modification time. */
    CAPTURE_DATE,

    /** The source can disappear at any time (USB detach, network loss). */
    TRANSIENT,

    /** The source exposes device properties (PTP DevicePropDesc) - future camera settings UI. */
    DEVICE_PROPERTIES,

    /** The source can push events (PTP ObjectAdded etc.) - future tethered capture. */
    EVENTS
}

data class SourceDescriptor(
    val id: SourceId,
    /** Short label for the source selector, e.g. "NIKON Z 6_2" or "RapidRawCloud: photos". */
    val displayName: String,
    /** Secondary line, e.g. "USB, 412 objects" or the bucket endpoint. */
    val detail: String = ""
)

/**
 * One RAW object inside a source. [ref] is the source's own locator (MediaStore
 * id, SAF document id, PTP object handle, S3 key) and is opaque to everyone
 * else. [nativeUri] is set when the platform can already serve the bytes
 * (MediaStore/SAF); otherwise [contentUri] routes through [SourceContentProvider].
 */
data class RawEntry(
    val source: SourceId,
    val ref: String,
    val name: String,
    val size: Long,
    val modifiedEpochSec: Long,
    val captureEpochSec: Long? = null,
    /** Folder/path inside the source, for display and ledger provenance. */
    val folder: String = "",
    val nativeUri: Uri? = null
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    val baseName: String get() = name.substringBeforeLast('.')

    /** The URI the rest of the app uses to read this entry. */
    val contentUri: Uri get() = nativeUri ?: SourceContentProvider.uriFor(this)
}

class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface RawSource {
    val descriptor: SourceDescriptor
    val capabilities: Set<SourceCapability>

    /** Connect / open a session. Idempotent. */
    suspend fun open() {}

    /** Release the session. Idempotent; the source may be reopened later. */
    suspend fun close() {}

    /**
     * List RAW objects whose extension is in [extensions] (lower-case, no dot).
     * Implementations should remember what they returned so [lookup] can
     * answer without a round trip.
     */
    suspend fun enumerate(extensions: Set<String>): List<RawEntry>

    /** Find an entry by [ref], consulting the source if it is not cached. */
    suspend fun lookup(ref: String): RawEntry?

    /** Stream the whole object. The caller closes the stream. */
    suspend fun openStream(entry: RawEntry): InputStream

    /** Read `[offset, offset+length)`; null when unsupported ([SourceCapability.PARTIAL_READ]). */
    suspend fun readRange(entry: RawEntry, offset: Long, length: Int): ByteArray? = null

    /** A small JPEG for the grid, when the source has one for free. */
    suspend fun thumbnail(entry: RawEntry): ByteArray? = null

    /** Copy the whole object into [dest]. */
    suspend fun fetchTo(entry: RawEntry, dest: File, onProgress: ((done: Long, total: Long) -> Unit)? = null) {
        openStream(entry).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    onProgress?.invoke(done, entry.size)
                }
            }
        }
    }
}

/**
 * Base class that keeps the last enumeration so [lookup] is cheap, and gives
 * subclasses a single place to resolve refs that were never enumerated in
 * this process (e.g. a URI restored from a saved fragment state).
 */
abstract class AbstractRawSource : RawSource {
    private val entries = LinkedHashMap<String, RawEntry>()

    protected fun remember(list: List<RawEntry>) {
        synchronized(entries) {
            entries.clear()
            list.forEach { entries[it.ref] = it }
        }
    }

    protected fun remembered(ref: String): RawEntry? = synchronized(entries) { entries[ref] }

    override suspend fun lookup(ref: String): RawEntry? {
        remembered(ref)?.let { return it }
        val fetched = fetchEntry(ref) ?: return null
        synchronized(entries) { entries[ref] = fetched }
        return fetched
    }

    /** Resolve a ref against the source itself; null when it no longer exists. */
    protected abstract suspend fun fetchEntry(ref: String): RawEntry?
}
