package com.springcat.filestore

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Metadata for one saved file. Backed directly by a [File] on disk, so listing
 * never touches the file contents — only name / size / date. That is what keeps
 * the app light even when the store holds multi-gigabyte files.
 */
data class StoredFile(
    val file: File,
    val name: String,
    val size: Long,
    val lastModified: Long
)

/**
 * Owns the on-disk store. There is intentionally no database: the store
 * directory *is* the source of truth, and directory listing gives us all the
 * metadata we show. Import/export stream bytes in fixed-size chunks so memory
 * use stays constant regardless of file size.
 */
class FileRepository(private val context: Context) {

    private val bufferSize = 64 * 1024 // 64 KB chunks — constant memory per copy.

    /** App-specific storage; needs no runtime permission and is cleaned up on uninstall. */
    fun storeDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "store")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Lists saved files, newest first. Cheap: only stats the directory entries. */
    fun list(): List<StoredFile> {
        val files = storeDir().listFiles() ?: return emptyList()
        return files
            .filter { it.isFile }
            .map { StoredFile(it, it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.lastModified }
    }

    fun totalSize(): Long = (storeDir().listFiles() ?: emptyArray())
        .filter { it.isFile }
        .sumOf { it.length() }

    /**
     * Copies the content behind [uri] into the store, streaming through a small
     * buffer. [onProgress] receives bytesCopied (and total, -1 if unknown) so the
     * UI can show progress without the copy ever holding the whole file in memory.
     */
    fun importFile(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): StoredFile {
        val displayName = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
        val target = uniqueTarget(displayName)
        val total = querySize(uri)

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the selected file." }
            target.outputStream().use { output ->
                val buffer = ByteArray(bufferSize)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(copied, total)
                }
                output.flush()
            }
        }
        return StoredFile(target, target.name, target.length(), target.lastModified())
    }

    fun delete(stored: StoredFile): Boolean = stored.file.delete()

    /** Avoids clobbering an existing file by appending " (n)" before the extension. */
    private fun uniqueTarget(name: String): File {
        val dir = storeDir()
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                }
            }
        return -1L
    }

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes < 0) return "?"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024 && unit < units.size - 1) {
                value /= 1024
                unit++
            }
            return if (unit == 0) "$bytes B" else String.format("%.1f %s", value, units[unit])
        }
    }
}
