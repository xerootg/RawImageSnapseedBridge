package com.raw2dng.source

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes every [RawEntry] of every registered [RawSource] as a `content://`
 * URI, so the preview dialog, EXIF reader, thumbnail cache, share sheet and
 * the converter can treat a camera object or a cloud object exactly like a
 * MediaStore row.
 *
 * URI layout: `content://<authority>/<encoded source id>/<encoded ref>?n=<file name>`.
 *
 * - `openFile` materialises the object through [SourceFetchCache] (one
 *   transfer, then local reads).
 * - `openTypedAssetFile` with [android.content.ContentResolver.EXTRA_SIZE]
 *   serves the source's cheap thumbnail (PTP GetThumb, RRC thumb), which is
 *   what `ContentResolver.loadThumbnail` asks for.
 * - `query` answers the OpenableColumns + the few MediaStore columns the app
 *   reads; `DATA` is the cached file when it is already materialised.
 *
 * The provider is not exported; URIs are shared with other apps only through
 * explicit grant flags (never done today; conversions are shared via MediaStore).
 */
class SourceContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "SourceContentProvider"
        const val AUTHORITY = "com.raw2dng.sources"
        private const val PARAM_NAME = "n"

        /** `MediaStore.MediaColumns.DATA` (deprecated since Q, still what ThumbnailCache asks for). */
        private const val COLUMN_DATA = "_data"

        fun uriFor(entry: RawEntry): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(entry.source.encode())
            .appendPath(entry.ref)
            .appendQueryParameter(PARAM_NAME, entry.name)
            .build()

        fun isSourceUri(uri: Uri?): Boolean = uri != null && uri.scheme == "content" && uri.authority == AUTHORITY

        /** (source id, ref) from a provider URI, or null for anything else. */
        fun parse(uri: Uri): Pair<SourceId, String>? {
            if (!isSourceUri(uri)) return null
            val segs = uri.pathSegments
            if (segs.size < 2) return null
            val id = SourceId.decode(segs[0]) ?: return null
            // Refs may contain slashes (S3 keys); re-join everything after the source id.
            val ref = segs.drop(1).joinToString("/")
            return id to ref
        }

        fun nameOf(uri: Uri): String? = uri.getQueryParameter(PARAM_NAME)

        fun mimeTypeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "dng" -> "image/x-adobe-dng"
                "" -> "application/octet-stream"
                else -> "image/x-$ext"
            }
        }
    }

    override fun onCreate(): Boolean {
        context?.let { SourceRegistry.init(it) }
        return true
    }

    private fun resolve(uri: Uri): Pair<RawSource, RawEntry> {
        val ctx = context ?: throw FileNotFoundException("no context")
        SourceRegistry.init(ctx)
        return runBlocking { SourceRegistry.entryFor(uri) }
            ?: throw FileNotFoundException("Unknown source entry: $uri")
    }

    override fun getType(uri: Uri): String = mimeTypeFor(nameOf(uri) ?: parse(uri)?.second ?: "")

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val cols = projection ?: arrayOf(
            OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE,
            COLUMN_DATA
        )
        val cursor = MatrixCursor(cols, 1)
        val (_, entry) = try { resolve(uri) } catch (e: FileNotFoundException) { return cursor }
        val ctx = context!!
        val cached = SourceFetchCache.cachedFile(ctx, entry).takeIf { it.exists() }
        val row = cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> entry.name
                OpenableColumns.SIZE -> entry.size
                MediaStore.MediaColumns.DATE_MODIFIED -> entry.modifiedEpochSec
                MediaStore.MediaColumns.MIME_TYPE -> mimeTypeFor(entry.name)
                MediaStore.MediaColumns.ORIENTATION -> 0
                COLUMN_DATA -> cached?.absolutePath
                MediaStore.MediaColumns._ID -> entry.ref.hashCode().toLong()
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.contains('w')) throw SecurityException("Sources are read-only")
        val (source, entry) = resolve(uri)
        val file: File = try {
            runBlocking { SourceFetchCache.materialize(context!!, source, entry) }
        } catch (e: Exception) {
            Log.e(TAG, "materialize failed for $uri", e)
            throw FileNotFoundException("Could not fetch ${entry.name}: ${e.message}")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor {
        val wantsThumbnail = opts?.containsKey(android.content.ContentResolver.EXTRA_SIZE) == true
        if (wantsThumbnail) {
            val (source, entry) = resolve(uri)
            if (SourceCapability.THUMBNAIL in source.capabilities) {
                val thumb = try { runBlocking { source.thumbnail(entry) } } catch (e: Exception) {
                    Log.w(TAG, "thumbnail failed for ${entry.name}: ${e.message}"); null
                }
                if (thumb != null && thumb.isNotEmpty()) {
                    val f = File(context!!.cacheDir, "source_thumbs").apply { mkdirs() }
                    val tf = File(f, SourceFetchCache.cachedFile(context!!, entry).nameWithoutExtension + ".jpg")
                    if (!tf.exists() || tf.length() != thumb.size.toLong()) tf.writeBytes(thumb)
                    return AssetFileDescriptor(
                        ParcelFileDescriptor.open(tf, ParcelFileDescriptor.MODE_READ_ONLY), 0, tf.length()
                    )
                }
            }
            // No cheap thumbnail: tell the resolver so callers fall back to the native decoder.
            throw FileNotFoundException("No thumbnail for $uri")
        }
        return super.openTypedAssetFile(uri, mimeTypeFilter, opts, signal)
            ?: throw FileNotFoundException("No typed asset for $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("read-only")
}
