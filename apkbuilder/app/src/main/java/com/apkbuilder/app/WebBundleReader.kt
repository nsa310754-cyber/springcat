package com.apkbuilder.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** Reads a user-chosen folder (SAF tree) of web game files into `assets/...` overrides. */
object WebBundleReader {

    /** Reads a single chosen HTML file as the entire game — the common case for a one-file game. */
    fun readSingleHtmlFile(resolver: ContentResolver, uri: Uri): Map<String, ByteArray> {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("could not read the chosen file")
        return mapOf("assets/game.html" to bytes)
    }

    /** Reads a whole folder tree (HTML/JS/CSS/images/...) as the game bundle. Requires an index.html or game.html at the root. */
    fun readFolder(context: Context, treeUri: Uri): Map<String, ByteArray> {
        val resolver = context.contentResolver
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("could not open the chosen folder")
        val files = LinkedHashMap<String, ByteArray>()
        collect(resolver, root, "", files)

        val entryPoint = files.keys.firstOrNull { it.equals("game.html", ignoreCase = true) }
            ?: files.keys.firstOrNull { it.equals("index.html", ignoreCase = true) }
            ?: error("the chosen folder has no game.html or index.html at its top level")

        if (!entryPoint.equals("game.html", ignoreCase = true)) {
            files["game.html"] = files.remove(entryPoint)!!
        }

        return files.mapKeys { (relativePath, _) -> "assets/$relativePath" }
    }

    private fun collect(resolver: ContentResolver, dir: DocumentFile, prefix: String, out: MutableMap<String, ByteArray>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                collect(resolver, child, relativePath, out)
            } else {
                val bytes = resolver.openInputStream(child.uri)?.use { it.readBytes() } ?: continue
                out[relativePath] = bytes
            }
        }
    }
}
