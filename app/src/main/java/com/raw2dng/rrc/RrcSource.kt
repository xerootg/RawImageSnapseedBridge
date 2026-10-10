package com.raw2dng.rrc

import android.content.Context
import android.util.Log
import com.raw2dng.source.AbstractRawSource
import com.raw2dng.source.RawEntry
import com.raw2dng.source.SourceCapability
import com.raw2dng.source.SourceDescriptor
import com.raw2dng.source.SourceException
import com.raw2dng.source.SourceId
import com.raw2dng.source.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * A RapidRawCloud library as a [com.raw2dng.source.RawSource]. Read-only
 * consumer of the bucket layout in RapidRawCloud's ARCHITECTURE.md §1.2:
 *
 * - `library/<relpath>` originals are the entries (sidecars `.rrdata`/`.xmp`,
 *   conflict copies and engine temp files are skipped).
 * - `.rrcloud/v1/tombstones/<hash>.json` name soft-deleted keys whose bytes may
 *   still be in the bucket during the grace window; they are excluded.
 * - `.rrcloud/v1/manifests/<device>.json.gz` (gzip NDJSON) map relkeys to
 *   `content_id`, which keys `.rrcloud/v1/thumbs/<content_id>_small.jpg`:
 *   free 480px thumbnails without downloading the RAW.
 *
 * This app never writes to the bucket: no journal entries, no device
 * registration. It is a reader exactly like `rclone`.
 */
class RrcSource(private val context: Context, val config: RrcConfig) : AbstractRawSource() {

    companion object {
        private const val TAG = "RrcSource"
        const val LIBRARY_PREFIX = "library/"
        const val CONTROL_PREFIX = ".rrcloud/v1/"
        private const val TOMBSTONES = "${CONTROL_PREFIX}tombstones/"
        private const val MANIFESTS = "${CONTROL_PREFIX}manifests/"
        private const val THUMBS = "${CONTROL_PREFIX}thumbs/"
        private const val MAX_TOMBSTONES = 2000

        fun idFor(config: RrcConfig) = SourceId(SourceKind.RRC, "${config.bucket}@${config.host}")

        /** Keys that are not originals even though they live under `library/`. */
        fun isOriginalKey(key: String): Boolean {
            val name = key.substringAfterLast('/')
            if (name.isEmpty() || name.startsWith(".rr.")) return false
            if (name.endsWith(".rrdata") || name.endsWith(".xmp", ignoreCase = true)) return false
            if (Regex("\\.conflict-[0-9a-f]{6}\\.").containsMatchIn(name)) return false
            return true
        }
    }

    override val descriptor = SourceDescriptor(
        idFor(config),
        "RapidRawCloud: ${config.bucket}",
        listOfNotNull(config.username, config.host).joinToString(" @ ")
    )

    override val capabilities: Set<SourceCapability> = setOf(SourceCapability.THUMBNAIL, SourceCapability.PARTIAL_READ)

    private val client: S3Client by lazy {
        val creds = RrcConfig.credentials(context) ?: throw SourceException("RapidRawCloud credentials are missing; pair again")
        S3Client(config.endpoint, config.bucket, config.region, creds, config.forcePathStyle)
    }

    /** relkey -> content_id, from the manifests; refreshed on every enumerate. */
    @Volatile
    private var contentIds: Map<String, String> = emptyMap()

    private val tombstoneCache = HashMap<String, String>()  // tombstone key@etag -> relkey

    override suspend fun enumerate(extensions: Set<String>): List<RawEntry> = withContext(Dispatchers.IO) {
        val objects = try { client.list(LIBRARY_PREFIX) } catch (e: S3Exception) {
            throw SourceException("Cannot list ${config.bucket}: ${e.message}", e)
        }
        val deleted = loadTombstones()
        val out = ArrayList<RawEntry>()
        for (o in objects) {
            if (!o.key.startsWith(LIBRARY_PREFIX) || !isOriginalKey(o.key)) continue
            val rel = o.key.removePrefix(LIBRARY_PREFIX)
            if (rel in deleted) continue
            val name = rel.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext !in extensions) continue
            out.add(
                RawEntry(
                    source = descriptor.id, ref = rel, name = name, size = o.size,
                    modifiedEpochSec = o.lastModifiedEpochSec,
                    folder = rel.substringBeforeLast('/', "")
                )
            )
        }
        out.sortByDescending { it.modifiedEpochSec }
        remember(out)
        // Thumbnail index is best effort and must never block the listing.
        try { contentIds = loadContentIds() } catch (e: Exception) { Log.w(TAG, "Manifests unavailable: ${e.message}") }
        Log.i(TAG, "Enumerated ${out.size} originals in ${config.bucket} (${deleted.size} tombstoned, ${contentIds.size} with thumbs)")
        out
    }

    private fun loadTombstones(): Set<String> {
        val listing = try { client.list(TOMBSTONES, MAX_TOMBSTONES) } catch (e: S3Exception) {
            Log.w(TAG, "Cannot list tombstones: ${e.message}"); return emptySet()
        }
        val out = HashSet<String>()
        for (o in listing) {
            val cacheKey = "${o.key}@${o.etag}"
            val rel = synchronized(tombstoneCache) { tombstoneCache[cacheKey] } ?: try {
                val doc = JSONObject(String(client.getBytes(o.key)))
                doc.optString("relkey").also { synchronized(tombstoneCache) { tombstoneCache[cacheKey] = it } }
            } catch (e: Exception) {
                Log.w(TAG, "Bad tombstone ${o.key}: ${e.message}"); continue
            }
            if (rel.isNotEmpty()) out.add(rel)
        }
        return out
    }

    private fun loadContentIds(): Map<String, String> {
        val listing = client.list(MANIFESTS, 200)
        val map = HashMap<String, String>()
        for (o in listing) {
            if (!o.key.endsWith(".json.gz")) continue
            GZIPInputStream(client.get(o.key)).bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val row = try { JSONObject(line) } catch (e: Exception) { continue }
                    if (row.optString("kind", "original") != "original") continue
                    val key = row.optString("key"); val cid = row.optString("content_id")
                    if (key.isNotEmpty() && cid.isNotEmpty()) map[key] = cid
                }
            }
        }
        return map
    }

    override suspend fun fetchEntry(ref: String): RawEntry? = withContext(Dispatchers.IO) {
        val listing = try { client.list(LIBRARY_PREFIX + ref, 2) } catch (e: S3Exception) { return@withContext null }
        val o = listing.firstOrNull { it.key == LIBRARY_PREFIX + ref } ?: return@withContext null
        RawEntry(
            source = descriptor.id, ref = ref, name = ref.substringAfterLast('/'), size = o.size,
            modifiedEpochSec = o.lastModifiedEpochSec, folder = ref.substringBeforeLast('/', "")
        )
    }

    override suspend fun openStream(entry: RawEntry): InputStream = withContext(Dispatchers.IO) {
        try { client.get(LIBRARY_PREFIX + entry.ref) } catch (e: S3Exception) {
            throw SourceException("Cannot download ${entry.name}: ${e.message}", e)
        }
    }

    override suspend fun readRange(entry: RawEntry, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        // Clamp to the object so a short file never produces a 416.
        var end = offset + length - 1
        if (entry.size > 0) end = minOf(end, entry.size - 1)
        if (end < offset) return@withContext ByteArray(0)
        try { client.getBytes(LIBRARY_PREFIX + entry.ref, offset..end) } catch (e: S3Exception) {
            Log.w(TAG, "Range read failed for ${entry.name}: ${e.message}"); null
        }
    }

    override suspend fun thumbnail(entry: RawEntry): ByteArray? = withContext(Dispatchers.IO) {
        val cid = contentIds[entry.ref] ?: return@withContext null
        try { client.getBytesOrNull("${THUMBS}${cid}_small.jpg") } catch (e: S3Exception) {
            Log.d(TAG, "Thumb fetch failed for ${entry.name}: ${e.message}"); null
        }
    }
}
