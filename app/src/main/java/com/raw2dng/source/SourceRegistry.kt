package com.raw2dng.source

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide registry of the sources the app currently knows about.
 *
 * Persistent sources (device library, saved document trees, a paired
 * RapidRawCloud bucket) are re-created on [init]; transient ones (USB cameras)
 * are added when the device is attached and removed when it goes away.
 */
object SourceRegistry {
    private const val TAG = "SourceRegistry"
    private const val PREFS = "raw2dng_sources"
    private const val KEY_ACTIVE = "active_source"
    private const val KEY_TREES = "document_trees"

    interface Listener {
        /** The set of sources changed (added/removed) or the active one switched. */
        fun onSourcesChanged()
    }

    private val sources = LinkedHashMap<String, RawSource>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var initialised = false

    @Volatile
    var activeId: SourceId? = null
        private set

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun notifyChanged() { listeners.forEach { it.onSourcesChanged() } }

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        val app = context.applicationContext
        register(DeviceMediaSource(app), notify = false)
        for (tree in savedTrees(app)) {
            try {
                register(DocumentTreeSource(app, Uri.parse(tree)), notify = false)
            } catch (e: Exception) {
                Log.w(TAG, "Dropping unusable document tree $tree", e)
            }
        }
        com.raw2dng.rrc.RrcConfig.load(app)?.let { cfg ->
            register(com.raw2dng.rrc.RrcSource(app, cfg), notify = false)
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_ACTIVE, null)?.let { SourceId.decode(it) }
        activeId = if (saved != null && sources.containsKey(saved.encode())) saved else DeviceMediaSource.ID
    }

    fun all(): List<RawSource> = synchronized(this) { sources.values.toList() }

    fun get(id: SourceId): RawSource? = synchronized(this) { sources[id.encode()] }

    fun active(): RawSource? = activeId?.let { get(it) } ?: get(DeviceMediaSource.ID)

    fun register(source: RawSource, notify: Boolean = true) {
        synchronized(this) { sources[source.descriptor.id.encode()] = source }
        Log.d(TAG, "Registered source ${source.descriptor.id} (${source.descriptor.displayName})")
        if (notify) notifyChanged()
    }

    fun unregister(context: Context, id: SourceId) {
        val removed = synchronized(this) { sources.remove(id.encode()) }
        if (removed != null) {
            runBlocking { runCatching { removed.close() } }
            if (activeId == id) setActive(context, DeviceMediaSource.ID)
            notifyChanged()
        }
    }

    fun setActive(context: Context, id: SourceId) {
        if (get(id) == null) return
        activeId = id
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, id.encode()).apply()
        notifyChanged()
    }

    // --- document trees (persisted) ---------------------------------------

    private fun savedTrees(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_TREES, emptySet()) ?: emptySet()

    fun addDocumentTree(context: Context, treeUri: Uri): DocumentTreeSource {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_TREES, savedTrees(app) + treeUri.toString()).apply()
        val source = DocumentTreeSource(app, treeUri)
        register(source)
        return source
    }

    fun removeDocumentTree(context: Context, source: DocumentTreeSource) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_TREES, savedTrees(app) - source.treeUri.toString()).apply()
        try {
            app.contentResolver.releasePersistableUriPermission(
                source.treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.d(TAG, "Permission for ${source.treeUri} was already gone")
        }
        unregister(app, source.descriptor.id)
    }

    // --- URI plumbing -----------------------------------------------------

    /** The entry behind a URI, if the URI belongs to a registered source. */
    suspend fun entryFor(uri: Uri): Pair<RawSource, RawEntry>? {
        val parsed = SourceContentProvider.parse(uri) ?: return null
        val source = get(parsed.first) ?: return null
        val entry = source.lookup(parsed.second) ?: return null
        return source to entry
    }

    /**
     * A local file with the bytes behind [uri], or null when the URI is not a
     * source URI (callers then fall back to the platform resolver).
     */
    suspend fun materialize(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        val (source, entry) = entryFor(uri) ?: return@withContext null
        SourceFetchCache.materialize(context, source, entry)
    }
}
