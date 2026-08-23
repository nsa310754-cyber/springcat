package com.apkbuilder.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Writes a freshly built APK to cache and fires the system installer — Android Studio's "Run". */
object Installer {
    fun install(context: Context, apkBytes: ByteArray, fileName: String) {
        val dir = File(context.cacheDir, "builds").apply { mkdirs() }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app" } + ".apk"
        val apkFile = File(dir, safeName)
        apkFile.writeBytes(apkBytes)

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
