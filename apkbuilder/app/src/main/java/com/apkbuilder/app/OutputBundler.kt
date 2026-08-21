package com.apkbuilder.app

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bundles the generated apk + keystore + credentials into one zip so a single SAF save covers everything. */
object OutputBundler {
    fun bundle(appLabel: String, apkBytes: ByteArray, keystore: com.apkbuilder.core.GeneratedKeystore): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("$appLabel.apk"))
            zip.write(apkBytes)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("$appLabel.keystore"))
            zip.write(keystore.pkcs12Bytes)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("keystore-info.txt"))
            zip.write(
                """
                alias=${keystore.alias}
                storePassword=${keystore.storePassword}
                keyPassword=${keystore.keyPassword}

                Keep this file! You need these values to re-sign future updates
                of the same app with the same signing identity.
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
