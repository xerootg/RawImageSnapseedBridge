package com.raw2dng.source

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/**
 * Local materialisation cache for entries whose bytes live on a camera or in
 * the cloud. [SourceContentProvider] serves `openFile` from here, so a PTP or
 * S3 object is transferred once and then reused by the preview, the EXIF
 * reader and the converter.
 *
 * LRU by access time, bounded by [maxBytes]. Files that are currently being
 * written are held under a per-key mutex so concurrent readers wait for one
 * transfer instead of starting their own.
 */
object SourceFetchCache {
    private const val TAG = "SourceFetchCache"
    private const val DIR = "source_fetch"

    /** Default budget; RAW files are 20-80 MB, so this keeps ~15-25 recent files. */
    @Volatile
    var maxBytes: Long = 768L * 1024 * 1024

    private val locks = HashMap<String, Mutex>()
    private val evictionMutex = Mutex()

    private fun dir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    private fun key(entry: RawEntry): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(entry.source.encode().toByteArray())
        md.update(0)
        md.update(entry.ref.toByteArray())
        md.update(0)
        md.update(entry.size.toString().toByteArray())
        md.update(0)
        md.update(entry.modifiedEpochSec.toString().toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun lockFor(key: String): Mutex = synchronized(locks) { locks.getOrPut(key) { Mutex() } }

    fun cachedFile(context: Context, entry: RawEntry): File {
        // Keep the original extension so the native backend can sniff the format.
        val ext = entry.extension.ifEmpty { "raw" }
        return File(dir(context), key(entry) + "." + ext)
    }

    fun isCached(context: Context, entry: RawEntry): Boolean {
        val f = cachedFile(context, entry)
        return f.exists() && f.length() == entry.size
    }

    /**
     * Return a local file holding the entry's bytes, transferring it from the
     * source if needed.
     */
    suspend fun materialize(
        context: Context,
        source: RawSource,
        entry: RawEntry,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File {
        val target = cachedFile(context, entry)
        lockFor(key(entry)).withLock {
            if (target.exists() && (entry.size <= 0 || target.length() == entry.size)) {
                target.setLastModified(System.currentTimeMillis())
                return target
            }
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.delete()
            try {
                Log.d(TAG, "Fetching ${entry.name} (${entry.size} B) from ${entry.source}")
                source.fetchTo(entry, tmp, onProgress)
                if (entry.size > 0 && tmp.length() != entry.size) {
                    throw SourceException("Short transfer for ${entry.name}: got ${tmp.length()} of ${entry.size} bytes")
                }
                if (!tmp.renameTo(target)) {
                    throw SourceException("Could not move ${tmp.name} into the fetch cache")
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }
        evictIfNeeded(context)
        return target
    }

    private suspend fun evictIfNeeded(context: Context) = evictionMutex.withLock {
        val files = dir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return@withLock
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return@withLock
        val target = (maxBytes * 0.8).toLong()
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= target) break
            val len = f.length()
            if (f.delete()) total -= len
        }
        Log.d(TAG, "Evicted fetch cache down to ${total / 1024 / 1024} MB")
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    fun sizeBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L
}
