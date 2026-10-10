package com.raw2dng.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale

/**
 * A Storage Access Framework document tree. This is how USB mass storage
 * (a card reader, a camera in mass-storage mode, an OTG drive) reaches the
 * app: Android mounts the volume itself and hands out a tree URI through
 * `ACTION_OPEN_DOCUMENT_TREE` / `StorageVolume.createOpenDocumentTreeIntent`.
 * Any folder works, which also covers SD cards and network providers.
 *
 * The tree is walked with raw DocumentsContract queries (one query per
 * directory) rather than DocumentFile, which issues one query per file.
 */
class DocumentTreeSource(private val context: Context, val treeUri: Uri) : AbstractRawSource() {

    companion object {
        private const val TAG = "DocumentTreeSource"
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        /** Folders that never hold camera RAWs; skipping them keeps a full-drive walk fast. */
        private val SKIP_DIRS = setOf("android", ".thumbnails", ".trashed", "lost.dir", "\$recycle.bin", "system volume information")

        fun idFor(treeUri: Uri): SourceId {
            val md = MessageDigest.getInstance("SHA-256").digest(treeUri.toString().toByteArray())
            return SourceId(SourceKind.DOCUMENT_TREE, md.joinToString("") { "%02x".format(it) }.take(16))
        }

        /** "primary:DCIM" -> "DCIM"; "1234-5678:" -> "1234-5678". */
        fun labelFor(treeUri: Uri): String {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return treeUri.lastPathSegment ?: "Folder"
            val volume = docId.substringBefore(':')
            val path = docId.substringAfter(':', "")
            return when {
                path.isEmpty() && volume == "primary" -> "Internal storage"
                path.isEmpty() -> "Volume $volume"
                volume == "primary" -> path
                else -> "$volume/$path"
            }
        }
    }

    override val descriptor = SourceDescriptor(
        idFor(treeUri),
        labelFor(treeUri),
        if (treeUri.authority == "com.android.externalstorage.documents") "USB / SD card" else treeUri.authority ?: ""
    )

    override val capabilities: Set<SourceCapability> = setOf(SourceCapability.PARTIAL_READ, SourceCapability.TRANSIENT)

    private fun docUri(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override suspend fun enumerate(extensions: Set<String>): List<RawEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<RawEntry>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<Pair<String, String>>()  // (documentId, display path)
        queue.add(rootId to "")
        var dirs = 0
        while (queue.isNotEmpty()) {
            val (dirId, path) = queue.poll() ?: break
            if (++dirs > 5000) { Log.w(TAG, "Stopping walk after 5000 directories"); break }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            val cursor = try {
                context.contentResolver.query(children, PROJECTION, null, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot list $path: ${e.message}"); null
            } ?: continue
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (name.lowercase(Locale.ROOT) !in SKIP_DIRS && !name.startsWith(".")) {
                            queue.add(id to if (path.isEmpty()) name else "$path/$name")
                        }
                        continue
                    }
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext !in extensions) continue
                    out.add(
                        RawEntry(
                            source = descriptor.id, ref = id, name = name,
                            size = if (c.isNull(3)) -1 else c.getLong(3),
                            modifiedEpochSec = if (c.isNull(4)) 0 else c.getLong(4) / 1000,
                            folder = path,
                            nativeUri = docUri(id)
                        )
                    )
                }
            }
        }
        out.sortByDescending { it.modifiedEpochSec }
        remember(out)
        out
    }

    override suspend fun fetchEntry(ref: String): RawEntry? = withContext(Dispatchers.IO) {
        context.contentResolver.query(docUri(ref), PROJECTION, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@withContext null
            val name = c.getString(1) ?: return@withContext null
            RawEntry(
                source = descriptor.id, ref = ref, name = name,
                size = if (c.isNull(3)) -1 else c.getLong(3),
                modifiedEpochSec = if (c.isNull(4)) 0 else c.getLong(4) / 1000,
                nativeUri = docUri(ref)
            )
        }
    }

    override suspend fun openStream(entry: RawEntry): InputStream =
        context.contentResolver.openInputStream(entry.contentUri)
            ?: throw SourceException("Cannot open ${entry.name}")

    override suspend fun readRange(entry: RawEntry, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(entry.contentUri)?.use { readRangeFromStream(it, offset, length) }
    }
}
