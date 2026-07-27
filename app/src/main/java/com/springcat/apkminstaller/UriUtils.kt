package com.springcat.apkminstaller

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object UriUtils {

    /** APKM 等のバンドル拡張子。 */
    private val BUNDLE_EXT = setOf("apkm", "apks", "xapk", "apk", "zip", "apkmirror")

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "file"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "unknown"
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isSupportedBundle(name: String): Boolean = extensionOf(name) in BUNDLE_EXT

    fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file.apk" }
}
