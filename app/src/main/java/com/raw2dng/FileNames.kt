package com.raw2dng

/**
 * Helpers for turning untrusted display names (MediaStore DISPLAY_NAME, names
 * from other apps' content providers) into safe file names.
 *
 * A display name is only ever used as the last path segment of a file we
 * create, so path separators, NUL bytes and other control characters must
 * never survive, and the result must never be empty or a dot-only name.
 */
object FileNames {
    private const val MAX_LENGTH = 200
    private const val FALLBACK = "file"

    /** Characters that are illegal in file names on at least one supported file system. */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]")

    /**
     * Sanitize [name] for use as a single file-name component.
     * Keeps the extension where one exists so that type detection keeps working.
     */
    fun sanitize(name: String): String {
        // Anything after the last separator is the actual name; everything else is a path.
        val lastSegment = name.substringAfterLast('/').substringAfterLast('\\')
        var cleaned = ILLEGAL.replace(lastSegment, "_").trim().trimEnd('.')
        if (cleaned.isEmpty() || cleaned.all { it == '.' }) {
            cleaned = FALLBACK
        }
        if (cleaned.length > MAX_LENGTH) {
            val ext = cleaned.substringAfterLast('.', "")
            val base = cleaned.substringBeforeLast('.')
            cleaned = if (ext.isNotEmpty() && ext.length < 16) {
                base.take(MAX_LENGTH - ext.length - 1) + "." + ext
            } else {
                cleaned.take(MAX_LENGTH)
            }
        }
        return cleaned
    }

    /** Base name (without extension) of a sanitized display name. */
    fun baseName(name: String): String = sanitize(name).substringBeforeLast('.')

    /** Lower-case extension of a sanitized display name, or [default] when it has none. */
    fun extension(name: String, default: String): String {
        val clean = sanitize(name)
        val ext = clean.substringAfterLast('.', "").lowercase()
        return if (ext.isEmpty() || ext == clean.lowercase()) default else ext
    }
}
