package com.springcat.apkdoctor.apk

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP helpers shaped around APK quirks (entry compression must be preserved). */
object Zips {

    private const val BUFFER_SIZE = 64 * 1024

    /** Signature-related JAR entries, which are stale the moment we re-sign. */
    fun isSignatureEntry(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC")
    }

    fun readEntry(file: File, name: String): ByteArray? =
        runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(name) ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrNull()

    /** True when [name] exists and is deflated rather than stored. */
    fun isCompressed(file: File, name: String): Boolean =
        runCatching {
            ZipFile(file).use { zip ->
                zip.getEntry(name)?.method == ZipEntry.DEFLATED
            }
        }.getOrDefault(false)

    fun entryNames(file: File): List<String> =
        runCatching {
            ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
        }.getOrDefault(emptyList())

    /** Reads the central directory; the message explains why an APK is unreadable. */
    fun probe(file: File): String? =
        runCatching {
            ZipFile(file).use { zip ->
                if (!zip.entries().hasMoreElements()) return "ZIPにエントリがありません"
            }
            null
        }.getOrElse { it.message ?: it.javaClass.simpleName }

    /**
     * Copies [source] into [dest], swapping in [replacements] and dropping entries
     * rejected by [keep]. Each entry keeps its original compression method so that
     * `resources.arsc` and uncompressed `.so` files stay installable on Android 11+,
     * unless [forceStored] asks for it to be written uncompressed.
     */
    fun repack(
        source: File,
        dest: File,
        replacements: Map<String, ByteArray> = emptyMap(),
        keep: (String) -> Boolean = { true },
        forceStored: (String) -> Boolean = { false },
    ) {
        val written = HashSet<String>()
        ZipFile(source).use { zip ->
            zipOutput(dest) { out ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (!keep(name) || !written.add(name)) continue
                    val replacement = replacements[name]
                    if (replacement != null) {
                        // Rewritten entries are small; deflate keeps the APK compact.
                        val method = if (forceStored(name)) ZipEntry.STORED else ZipEntry.DEFLATED
                        out.writeEntry(name, replacement, method)
                    } else if (entry.isDirectory) {
                        out.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = 0; compressedSize = 0; crc = 0 })
                        out.closeEntry()
                    } else {
                        val method = if (forceStored(name)) ZipEntry.STORED else entry.method
                        zip.getInputStream(entry).use { input ->
                            out.copyEntry(name, method, input)
                        }
                    }
                }
                for ((name, bytes) in replacements) {
                    if (!written.add(name)) continue
                    out.writeEntry(name, bytes, if (forceStored(name)) ZipEntry.STORED else ZipEntry.DEFLATED)
                }
            }
        }
    }

    /**
     * Rebuilds an archive by walking local file headers, recovering what is
     * readable when the central directory is damaged or truncated.
     * Returns the number of entries salvaged.
     */
    fun salvage(source: File, dest: File, keep: (String) -> Boolean = { true }): Int {
        var count = 0
        val written = HashSet<String>()
        zipOutput(dest) { out ->
            ZipInputStream(BufferedInputStream(source.inputStream(), BUFFER_SIZE)).use { input ->
                while (true) {
                    val entry = try {
                        input.nextEntry ?: break
                    } catch (e: Exception) {
                        // Trailing garbage after the last good entry: stop here.
                        break
                    }
                    val name = entry.name
                    if (entry.isDirectory || !keep(name) || !written.add(name)) continue
                    val bytes = try {
                        input.readBytes()
                    } catch (e: Exception) {
                        written.remove(name)
                        continue
                    }
                    // The original method is unknown here, so recompress everything
                    // except the entries Android requires to stay uncompressed.
                    val method = if (mustStayUncompressed(name)) ZipEntry.STORED else ZipEntry.DEFLATED
                    out.writeEntry(name, bytes, method)
                    count++
                }
            }
        }
        return count
    }

    /** Android 11+ rejects APKs whose `resources.arsc` is compressed. */
    fun mustStayUncompressed(name: String): Boolean = name == "resources.arsc"

    private inline fun zipOutput(dest: File, body: (ZipOutputStream) -> Unit) {
        dest.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(dest), BUFFER_SIZE)).use(body)
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray, method: Int) {
        val entry = ZipEntry(name)
        entry.method = method
        if (method == ZipEntry.STORED) {
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = CRC32().apply { update(bytes) }.value
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.copyEntry(name: String, method: Int, input: InputStream) {
        if (method == ZipEntry.STORED) {
            // STORED needs size and CRC up front, so the entry is buffered.
            val bytes = input.readBytes()
            writeEntry(name, bytes, ZipEntry.STORED)
            return
        }
        putNextEntry(ZipEntry(name).apply { this.method = ZipEntry.DEFLATED })
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            write(buffer, 0, read)
        }
        closeEntry()
    }
}
