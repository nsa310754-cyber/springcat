package com.springcat.ide.core.file

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A source file inside the app's private workspace. */
data class WorkspaceFile(val file: File) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

/**
 * Manages plain-text source files under the app's private storage. Nothing here
 * needs runtime storage permissions — exports to shared storage go through the
 * Storage Access Framework instead.
 */
class WorkspaceStore(filesDir: File) {

    private val root: File = File(filesDir, "workspace").apply { mkdirs() }

    fun list(): List<WorkspaceFile> =
        root.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name.lowercase() }
            ?.map(::WorkspaceFile)
            ?: emptyList()

    fun read(file: WorkspaceFile): String = file.file.readText()

    /**
     * Write every workspace file into a ZIP archive (e.g. a SAF document) so the
     * whole project can be saved or shared as a single `.zip`.
     *
     * @return the number of files archived
     */
    fun exportZip(out: OutputStream): Int {
        val files = list()
        ZipOutputStream(out.buffered()).use { zos ->
            for (wf in files) {
                zos.putNextEntry(ZipEntry(wf.name))
                wf.file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return files.size
    }

    fun write(name: String, content: String): WorkspaceFile {
        val safe = sanitize(name)
        val target = File(root, safe)
        target.writeText(content)
        return WorkspaceFile(target)
    }

    fun create(name: String): WorkspaceFile {
        val safe = sanitize(name)
        val target = File(root, safe)
        if (!target.exists()) target.writeText(starterContentFor(safe))
        return WorkspaceFile(target)
    }

    fun delete(file: WorkspaceFile): Boolean = file.file.delete()

    fun exists(name: String): Boolean = File(root, sanitize(name)).exists()

    private fun sanitize(name: String): String =
        name.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "untitled.txt" }

    private fun starterContentFor(name: String): String = when {
        name.endsWith(".kt") -> "fun main() {\n    println(\"Hello from SpringCat\")\n}\n"
        name.endsWith(".py") -> "def main():\n    print(\"Hello from SpringCat\")\n\n\nif __name__ == \"__main__\":\n    main()\n"
        name.endsWith(".java") -> "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello from SpringCat\");\n    }\n}\n"
        name.endsWith(".js") || name.endsWith(".ts") -> "console.log(\"Hello from SpringCat\");\n"
        else -> ""
    }
}
