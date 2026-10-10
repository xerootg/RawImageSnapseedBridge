package com.raw2dng.source

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale

/**
 * The phone's own media library. This is the source the app always had; it is
 * now just one [RawSource] among others. Entries keep their MediaStore URI as
 * [RawEntry.nativeUri] so the OS thumbnailer and the platform resolver keep
 * serving them directly.
 */
class DeviceMediaSource(private val context: Context) : AbstractRawSource() {

    companion object {
        val ID = SourceId(SourceKind.DEVICE_MEDIA, "local")

        /** RELATIVE_PATH exists from API 29; older devices get BUCKET_DISPLAY_NAME. */
        private val PATH_COLUMN: String =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH
            else MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            PATH_COLUMN
        )
    }

    override val descriptor = SourceDescriptor(ID, "This device", "Photos on this phone")
    override val capabilities: Set<SourceCapability> = setOf(SourceCapability.PARTIAL_READ, SourceCapability.CAPTURE_DATE)

    override suspend fun enumerate(extensions: Set<String>): List<RawEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<RawEntry>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, PROJECTION, null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val takenCol = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val pathCol = c.getColumnIndex(PATH_COLUMN)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext !in extensions) continue
                out.add(rowToEntry(c, idCol, name, sizeCol, dateCol, takenCol, pathCol))
            }
        }
        remember(out)
        out
    }

    private fun rowToEntry(c: android.database.Cursor, idCol: Int, name: String, sizeCol: Int, dateCol: Int, takenCol: Int, pathCol: Int): RawEntry {
        val id = c.getLong(idCol)
        val taken = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) / 1000 else null
        return RawEntry(
            source = ID,
            ref = id.toString(),
            name = name,
            size = c.getLong(sizeCol),
            modifiedEpochSec = c.getLong(dateCol),
            captureEpochSec = taken?.takeIf { it > 0 },
            folder = if (pathCol >= 0) c.getString(pathCol) ?: "" else "",
            nativeUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        )
    }

    override suspend fun fetchEntry(ref: String): RawEntry? = withContext(Dispatchers.IO) {
        val id = ref.toLongOrNull() ?: return@withContext null
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        context.contentResolver.query(uri, PROJECTION, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@withContext null
            val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: return@withContext null
            rowToEntry(
                c, c.getColumnIndexOrThrow(MediaStore.Images.Media._ID), name,
                c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE),
                c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED),
                c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN),
                c.getColumnIndex(PATH_COLUMN)
            )
        }
    }

    /** Build an entry for an arbitrary MediaStore image URI (used by the gallery's regenerate). */
    suspend fun entryForUri(uri: Uri): RawEntry? = lookup(ContentUris.parseId(uri).toString())

    override suspend fun openStream(entry: RawEntry): InputStream =
        context.contentResolver.openInputStream(entry.contentUri)
            ?: throw SourceException("Cannot open ${entry.name}")

    override suspend fun readRange(entry: RawEntry, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(entry.contentUri)?.use { readRangeFromStream(it, offset, length) }
    }
}

/** Shared helper: read `[offset, offset+length)` from a fresh stream. */
internal fun readRangeFromStream(input: InputStream, offset: Long, length: Int): ByteArray {
    var toSkip = offset
    while (toSkip > 0) {
        val s = input.skip(toSkip)
        if (s <= 0) {
            if (input.read() < 0) break
            toSkip -= 1
        } else toSkip -= s
    }
    val buf = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = input.read(buf, read, length - read)
        if (n < 0) break
        read += n
    }
    return if (read == length) buf else buf.copyOf(read)
}

/** Public alias so other packages can reuse the range reader. */
fun readRangeFromStreamPublic(input: InputStream, offset: Long, length: Int): ByteArray = readRangeFromStream(input, offset, length)
