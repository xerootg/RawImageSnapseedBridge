package com.raw2dng.ptp

import android.content.Context
import android.util.Log
import com.raw2dng.source.AbstractRawSource
import com.raw2dng.source.RawEntry
import com.raw2dng.source.SourceCapability
import com.raw2dng.source.SourceDescriptor
import com.raw2dng.source.SourceException
import com.raw2dng.source.SourceFetchCache
import com.raw2dng.source.SourceId
import com.raw2dng.source.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * A camera reached over PTP, as a [com.raw2dng.source.RawSource]. Transport
 * agnostic: give it a factory that opens a [PtpTransport] (USB today, PTP/IP
 * later) and it does the rest. The source identity is the camera's serial
 * number, so a camera is the "same source" over USB and Wi-Fi and its ledger
 * entries carry over.
 *
 * Object refs are `"<storageId hex>/<handle hex>"`. Handles are only stable
 * within a session, so the ledger fingerprint cache additionally keys on
 * name/size/date and a reopened source re-enumerates.
 */
class PtpSource(
    private val context: Context,
    private val transportFactory: () -> PtpTransport,
    /** Fallback label before the session is open (e.g. USB product name). */
    private val provisionalLabel: String,
    /** Fallback identity before DeviceInfo is available (e.g. "usb:04b0:0443"). */
    private val provisionalInstance: String
) : AbstractRawSource() {

    companion object {
        private const val TAG = "PtpSource"
        private const val THUMB_LIMIT_BYTES = 2 * 1024 * 1024

        fun refFor(storageId: Int, handle: Int) = "%08x/%08x".format(storageId, handle)
        fun parseRef(ref: String): Pair<Int, Int>? {
            val parts = ref.split('/')
            if (parts.size != 2) return null
            val s = parts[0].toLongOrNull(16) ?: return null
            val h = parts[1].toLongOrNull(16) ?: return null
            return s.toInt() to h.toInt()
        }
    }

    private var session: PtpSession? = null
    private val infoByRef = HashMap<String, PtpObjectInfo>()

    @Volatile
    override var descriptor: SourceDescriptor = SourceDescriptor(SourceId(SourceKind.PTP, provisionalInstance), provisionalLabel, "PTP camera")
        private set

    @Volatile
    override var capabilities: Set<SourceCapability> = setOf(SourceCapability.THUMBNAIL, SourceCapability.CAPTURE_DATE, SourceCapability.TRANSIENT)
        private set

    val isOpen: Boolean get() = session?.isOpen == true

    override suspend fun open() = withContext(Dispatchers.IO) {
        if (session?.isOpen == true) return@withContext
        val s = PtpSession(transportFactory())
        try {
            val info = s.open()
            session = s
            val instance = info.serialNumber.trim().ifEmpty { provisionalInstance }
            val label = listOf(info.manufacturer.trim(), info.model.trim()).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { provisionalLabel }
            descriptor = SourceDescriptor(SourceId(SourceKind.PTP, instance), label, s.transport.description)
            val caps = mutableSetOf(SourceCapability.THUMBNAIL, SourceCapability.CAPTURE_DATE, SourceCapability.TRANSIENT)
            if (s.supportsPartialObject()) caps.add(SourceCapability.PARTIAL_READ)
            if (info.devicePropertiesSupported.isNotEmpty()) caps.add(SourceCapability.DEVICE_PROPERTIES)
            if (info.eventsSupported.isNotEmpty()) caps.add(SourceCapability.EVENTS)
            capabilities = caps
        } catch (e: Exception) {
            s.close()
            throw SourceException("Cannot open camera: ${e.message}", e)
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        session?.close()
        session = null
    }

    private fun requireSession(): PtpSession = session?.takeIf { it.isOpen } ?: throw SourceException("Camera session is not open")

    override suspend fun enumerate(extensions: Set<String>): List<RawEntry> = withContext(Dispatchers.IO) {
        val s = requireSession()
        val out = ArrayList<RawEntry>()
        val seen = HashMap<String, PtpObjectInfo>()
        val storages = try { s.getStorageIds() } catch (e: PtpException) { throw SourceException("GetStorageIDs: ${e.message}", e) }
        for (storageId in storages) {
            // Logical store id 0 in the low word means "not present" (e.g. an empty card slot).
            if (storageId and 0xFFFF == 0) continue
            // Cameras disagree on what parent 0x00000000 means ("all objects" per PIMA 15740,
            // "nothing" or "root" on some firmware), so take the union of the flat listing and
            // a walk from the root. The walk costs one GetObjectHandles per folder - negligible
            // next to the per-object GetObjectInfo below.
            val handles = LinkedHashSet<Int>()
            try {
                s.getObjectHandles(storageId, PtpConstants.FORMAT_ANY, PtpConstants.HANDLE_ALL).forEach { handles.add(it) }
            } catch (e: PtpException) {
                Log.w(TAG, "GetObjectHandles(all) failed on store %08x: %s".format(storageId, e.message))
            }
            walk(s, storageId, handles)
            for (handle in handles) {
                val info = try { s.getObjectInfo(handle) } catch (e: PtpException) {
                    Log.w(TAG, "GetObjectInfo(%08x) failed: %s".format(handle, e.message)); continue
                }
                if (info.isFolder) continue
                val ext = info.filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext !in extensions) continue
                val ref = refFor(storageId, handle)
                seen[ref] = info
                out.add(
                    RawEntry(
                        source = descriptor.id, ref = ref, name = info.filename, size = info.compressedSize,
                        modifiedEpochSec = info.modificationDate ?: info.captureDate ?: 0L,
                        captureEpochSec = info.captureDate,
                        folder = "%08x".format(storageId)
                    )
                )
            }
        }
        synchronized(infoByRef) { infoByRef.clear(); infoByRef.putAll(seen) }
        out.sortByDescending { it.captureEpochSec ?: it.modifiedEpochSec }
        remember(out)
        Log.i(TAG, "Enumerated ${out.size} RAW objects on ${descriptor.displayName}")
        out
    }

    /** Depth-first walk from the root, adding every non-folder handle to [into]. */
    private fun walk(s: PtpSession, storageId: Int, into: MutableSet<Int>) {
        val stack = ArrayDeque<Int>()
        stack.add(PtpConstants.HANDLE_ROOT)
        var dirs = 0
        while (stack.isNotEmpty() && dirs++ < 2000) {
            val parent = stack.removeLast()
            // Folders only, then everything: two cheap queries per directory instead of one GetObjectInfo per child.
            val folders = try { s.getObjectHandles(storageId, PtpConstants.FMT_ASSOCIATION, parent) } catch (e: PtpException) { IntArray(0) }
            val children = try { s.getObjectHandles(storageId, PtpConstants.FORMAT_ANY, parent) } catch (e: PtpException) { continue }
            val folderSet = folders.toHashSet()
            for (h in children) {
                if (h in folderSet) stack.add(h) else into.add(h)
            }
        }
    }

    override suspend fun fetchEntry(ref: String): RawEntry? = withContext(Dispatchers.IO) {
        val (storageId, handle) = parseRef(ref) ?: return@withContext null
        val s = session?.takeIf { it.isOpen } ?: return@withContext null
        val info = try { s.getObjectInfo(handle) } catch (e: PtpException) { return@withContext null }
        synchronized(infoByRef) { infoByRef[ref] = info }
        RawEntry(
            source = descriptor.id, ref = ref, name = info.filename, size = info.compressedSize,
            modifiedEpochSec = info.modificationDate ?: info.captureDate ?: 0L, captureEpochSec = info.captureDate,
            folder = "%08x".format(storageId)
        )
    }

    private fun handleOf(entry: RawEntry): Int = parseRef(entry.ref)?.second ?: throw SourceException("Bad PTP ref ${entry.ref}")

    override suspend fun fetchTo(entry: RawEntry, dest: File, onProgress: ((Long, Long) -> Unit)?) = withContext(Dispatchers.IO) {
        val s = requireSession()
        FileOutputStream(dest).use { out ->
            try {
                s.getObject(handleOf(entry), out, onProgress)
            } catch (e: PtpException) {
                throw SourceException("Transfer of ${entry.name} failed: ${e.message}", e)
            }
        }
    }

    /** PTP has no stream semantics: transfer into the fetch cache and read from there. */
    override suspend fun openStream(entry: RawEntry): InputStream =
        SourceFetchCache.materialize(context, this, entry).inputStream()

    override suspend fun readRange(entry: RawEntry, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        val s = requireSession()
        if (!s.supportsPartialObject()) return@withContext null
        if (SourceFetchCache.isCached(context, entry)) {
            return@withContext SourceFetchCache.cachedFile(context, entry).inputStream().use { com.raw2dng.source.readRangeFromStreamPublic(it, offset, length) }
        }
        try {
            s.getPartialObject(handleOf(entry), offset, length)
        } catch (e: PtpException) {
            Log.w(TAG, "GetPartialObject failed for ${entry.name}: ${e.message}"); null
        }
    }

    override suspend fun thumbnail(entry: RawEntry): ByteArray? = withContext(Dispatchers.IO) {
        val s = requireSession()
        val info = synchronized(infoByRef) { infoByRef[entry.ref] }
        if (info != null && (!info.hasThumbnail || info.thumbCompressedSize > THUMB_LIMIT_BYTES)) return@withContext null
        try {
            s.getThumb(handleOf(entry)).takeIf { it.size > 2 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }
        } catch (e: PtpException) {
            Log.d(TAG, "GetThumb failed for ${entry.name}: ${e.message}"); null
        }
    }

    /** The open session, for camera-settings / tethering features layered on top. */
    fun session(): PtpSession? = session
}
