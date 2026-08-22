package com.apkbuilder.app

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bundles the generated apk + keystore + credentials (+ assetlinks.json for PWA mode) into one zip so a single SAF save covers everything. */
object OutputBundler {
    fun bundle(
        appLabel: String,
        apkBytes: ByteArray,
        keystore: com.apkbuilder.core.GeneratedKeystore,
        assetLinksJson: String? = null,
    ): ByteArray {
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
                sha256CertFingerprint=${keystore.certificateSha256Fingerprint}

                Keep this file! You need these values to re-sign future updates
                of the same app with the same signing identity.
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()

            if (assetLinksJson != null) {
                zip.putNextEntry(ZipEntry("assetlinks.json"))
                zip.write(assetLinksJson.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("assetlinks-README.txt"))
                zip.write(
                    """
                    このアプリはPWAモード(既存サイトのラップ)で作成されました。

                    同梱の assetlinks.json は、あなたのサイト側の
                        https://<あなたのドメイン>/.well-known/assetlinks.json
                    にそのままアップロードしてください(APK自身はあなたのサーバーへ
                    ファイルを配置できないため、このファイルは手動で設置する必要があります)。

                    これにより、Digital Asset Links の検証が通り、Chrome の
                    Trusted Web Activity 相当の挙動(URLバー無しの全画面表示など)が
                    有効になります。設置しなくてもアプリ自体は動作しますが、
                    検証は通りません。
                    """.trimIndent().toByteArray(),
                )
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
