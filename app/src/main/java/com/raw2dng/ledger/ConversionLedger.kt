package com.raw2dng.ledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import com.raw2dng.OutputFormat
import com.raw2dng.source.RawEntry
import com.raw2dng.source.SourceId
import java.io.File

/**
 * Local record of what has been converted to what, keyed by [RawFingerprint].
 *
 * The ledger is the answer to "we must know what we converted without
 * touching the source": nothing is ever written next to the RAW. It replaces
 * the old "does `Pictures/Raw2DNG/<base>.dng` exist" check, which broke as
 * soon as two cameras produced `DSC_0001.NEF` or a file was renamed.
 *
 * Rows are advisory: an output row whose file is gone (deleted from the
 * gallery, another app, a factory reset of the Pictures folder) is treated as
 * not converted and pruned lazily by [statusOf].
 *
 * Tables
 * - `entry_fp`: cache of (source, ref, name, size, mtime) -> fingerprint so
 *   re-listing a source does not re-read 64 KiB per file.
 * - `fp_alias`: metadata fingerprint -> content fingerprint, learned when a
 *   range-less source's file has been transferred in full.
 * - `conversion`: (fingerprint, format) -> output URI/name + provenance.
 */
class ConversionLedger private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "ConversionLedger"
        private const val DB_NAME = "conversion_ledger.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: ConversionLedger? = null

        fun get(context: Context): ConversionLedger =
            instance ?: synchronized(this) { instance ?: ConversionLedger(context).also { instance = it } }
    }

    /** Where one conversion went. */
    data class OutputRecord(
        val fingerprint: String,
        val format: OutputFormat,
        val outputUri: Uri,
        val outputName: String,
        val convertedAt: Long,
        val source: SourceId?,
        val sourceRef: String?,
        val sourceName: String
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entry_fp (
                source TEXT NOT NULL, ref TEXT NOT NULL, name TEXT NOT NULL,
                size INTEGER NOT NULL, mtime INTEGER NOT NULL, fp TEXT NOT NULL,
                PRIMARY KEY (source, ref))"""
        )
        db.execSQL("CREATE INDEX entry_fp_fp ON entry_fp(fp)")
        db.execSQL("CREATE TABLE fp_alias (meta_fp TEXT PRIMARY KEY, content_fp TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE conversion (
                fp TEXT NOT NULL, format TEXT NOT NULL,
                output_uri TEXT NOT NULL, output_name TEXT NOT NULL,
                converted_at INTEGER NOT NULL,
                source TEXT, source_ref TEXT, source_name TEXT NOT NULL,
                PRIMARY KEY (fp, format))"""
        )
        db.execSQL("CREATE INDEX conversion_output_name ON conversion(output_name)")
        db.execSQL("CREATE INDEX conversion_output_uri ON conversion(output_uri)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first schema; future migrations go here, never a drop.
    }

    // --- fingerprint cache ------------------------------------------------

    fun cachedFingerprint(entry: RawEntry): String? {
        readableDatabase.query(
            "entry_fp", arrayOf("fp", "name", "size", "mtime"), "source = ? AND ref = ?",
            arrayOf(entry.source.encode(), entry.ref), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            val same = c.getString(1) == entry.name && c.getLong(2) == entry.size && c.getLong(3) == entry.modifiedEpochSec
            return if (same) resolveAlias(c.getString(0)) else null
        }
    }

    fun rememberFingerprint(entry: RawEntry, fp: String) {
        val v = ContentValues().apply {
            put("source", entry.source.encode()); put("ref", entry.ref); put("name", entry.name)
            put("size", entry.size); put("mtime", entry.modifiedEpochSec); put("fp", fp)
        }
        writableDatabase.insertWithOnConflict("entry_fp", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Link a metadata fingerprint to the content fingerprint learned after a full transfer. */
    fun addAlias(metaFp: String, contentFp: String) {
        if (metaFp == contentFp || !RawFingerprint.isContent(contentFp)) return
        val v = ContentValues().apply { put("meta_fp", metaFp); put("content_fp", contentFp) }
        writableDatabase.insertWithOnConflict("fp_alias", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        // Move any conversions recorded under the weaker identity.
        writableDatabase.execSQL("UPDATE OR IGNORE conversion SET fp = ? WHERE fp = ?", arrayOf(contentFp, metaFp))
    }

    fun resolveAlias(fp: String): String {
        if (RawFingerprint.isContent(fp)) return fp
        readableDatabase.query("fp_alias", arrayOf("content_fp"), "meta_fp = ?", arrayOf(fp), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else fp
        }
    }

    // --- conversions ------------------------------------------------------

    fun record(entry: RawEntry, fp: String, format: OutputFormat, outputUri: Uri, outputName: String) {
        val v = ContentValues().apply {
            put("fp", resolveAlias(fp)); put("format", format.name)
            put("output_uri", outputUri.toString()); put("output_name", outputName)
            put("converted_at", System.currentTimeMillis() / 1000)
            put("source", entry.source.encode()); put("source_ref", entry.ref); put("source_name", entry.name)
        }
        writableDatabase.insertWithOnConflict("conversion", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Formats [fp] has been converted to and whose output still exists.
     * Stale rows are deleted so the UI and the ledger agree.
     */
    fun statusOf(context: Context, fp: String, exists: (Uri) -> Boolean = { defaultExists(context, it) }): Set<OutputFormat> {
        val key = resolveAlias(fp)
        val present = HashSet<OutputFormat>()
        val stale = ArrayList<String>()
        readableDatabase.query("conversion", arrayOf("format", "output_uri"), "fp = ?", arrayOf(key), null, null, null).use { c ->
            while (c.moveToNext()) {
                val format = runCatching { OutputFormat.valueOf(c.getString(0)) }.getOrNull() ?: continue
                val uri = Uri.parse(c.getString(1))
                if (exists(uri)) present.add(format) else stale.add(format.name)
            }
        }
        if (stale.isNotEmpty()) {
            Log.d(TAG, "Pruning ${stale.size} stale output(s) for $key")
            stale.forEach { writableDatabase.delete("conversion", "fp = ? AND format = ?", arrayOf(key, it)) }
        }
        return present
    }

    fun outputsOf(fp: String): List<OutputRecord> = queryOutputs("fp = ?", arrayOf(resolveAlias(fp)))

    /** Conversions that produced an output with this display name (gallery -> source lookup). */
    fun outputsNamed(outputName: String): List<OutputRecord> = queryOutputs("output_name = ?", arrayOf(outputName))

    fun outputsForUri(uri: Uri): List<OutputRecord> = queryOutputs("output_uri = ?", arrayOf(uri.toString()))

    fun forgetOutput(uri: Uri) {
        writableDatabase.delete("conversion", "output_uri = ?", arrayOf(uri.toString()))
    }

    private fun queryOutputs(where: String, args: Array<String>): List<OutputRecord> {
        val out = ArrayList<OutputRecord>()
        readableDatabase.query(
            "conversion",
            arrayOf("fp", "format", "output_uri", "output_name", "converted_at", "source", "source_ref", "source_name"),
            where, args, null, null, "converted_at DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val format = runCatching { OutputFormat.valueOf(c.getString(1)) }.getOrNull() ?: continue
                out.add(
                    OutputRecord(
                        fingerprint = c.getString(0), format = format,
                        outputUri = Uri.parse(c.getString(2)), outputName = c.getString(3),
                        convertedAt = c.getLong(4),
                        source = c.getString(5)?.let { SourceId.decode(it) },
                        sourceRef = c.getString(6), sourceName = c.getString(7)
                    )
                )
            }
        }
        return out
    }

    private fun defaultExists(context: Context, uri: Uri): Boolean = when (uri.scheme) {
        "file" -> uri.path?.let { File(it).exists() } == true
        "content" -> try {
            context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        } catch (e: Exception) { false }
        else -> false
    }
}
