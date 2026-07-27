package com.springcat.apkminstaller

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

class BundleException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * APKM / APKS / XAPK / ZIP (いずれも中身は ZIP) から .apk を取り出す。
 * 単体の .apk はそのままコピーする。
 */
object BundleExtractor {

    class Extracted(val apks: List<File>, val warnings: List<String>)

    fun extract(context: Context, uri: Uri, displayName: String, workDir: File): Extracted {
        workDir.mkdirs()
        return if (UriUtils.extensionOf(displayName) == "apk") {
            val out = File(workDir, UriUtils.sanitizeFileName(displayName))
            copyFrom(context, uri, out)
            Extracted(listOf(out), emptyList())
        } else {
            unzip(context, uri, workDir)
        }
    }

    private fun copyFrom(context: Context, uri: Uri, out: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw BundleException("ファイルを開けませんでした: $uri")
        input.use { i -> out.outputStream().use { o -> i.copyTo(o, BUFFER) } }
    }

    private fun unzip(context: Context, uri: Uri, workDir: File): Extracted {
        val apks = mutableListOf<File>()
        val warnings = mutableListOf<String>()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw BundleException("ファイルを開けませんでした: $uri")

        try {
            ZipInputStream(BufferedInputStream(input, BUFFER)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val base = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && base.isNotEmpty()) {
                        when {
                            base.endsWith(".apk", true) -> {
                                val out = uniqueFile(workDir, UriUtils.sanitizeFileName(base))
                                out.outputStream().use { o -> zis.copyTo(o, BUFFER) }
                                apks += out
                            }
                            base.endsWith(".obb", true) ->
                                warnings += "OBB は自動配置できないためスキップしました: $base"
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: ZipException) {
            throw BundleException(
                "ZIP を展開できませんでした。暗号化された APKM (APKMirror の新形式) か、ファイルが壊れている可能性があります。",
                e
            )
        }

        if (apks.isEmpty()) {
            throw BundleException("バンドルの中に .apk が見つかりませんでした。")
        }

        // 1 つも解析できない = 暗号化 or 破損の可能性が高い
        val parseable = apks.count { context.packageManager.getPackageArchiveInfo(it.absolutePath, 0) != null }
        if (parseable == 0) {
            throw BundleException(
                "取り出した APK を解析できませんでした。暗号化された APKM の可能性があります " +
                    "(APKMirror の暗号化 APKM は非対応です)。"
            )
        }
        return Extracted(apks.sortedBy { it.name }, warnings)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 1
        while (f.exists()) {
            f = File(dir, "${name.substringBeforeLast('.')}_$n.apk")
            n++
        }
        return f
    }

    private const val BUFFER = 128 * 1024
}
