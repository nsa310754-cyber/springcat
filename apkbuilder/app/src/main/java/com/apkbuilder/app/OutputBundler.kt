package com.apkbuilder.app

import com.apkbuilder.core.GeneratedKeystore
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages the generated artifact(s) plus the signing identity into one zip so
 * a single SAF save covers everything. Supports three shapes:
 *  - APK build  (apk + keystore + info, optional assetlinks.json for PWA)
 *  - AAB build  (aab + keystore + info)
 *  - Google Play submission package (aab + screenshots + 512 icon + a listing
 *    template + keystore + info)
 */
object OutputBundler {

    fun bundleApk(
        appLabel: String,
        apkBytes: ByteArray,
        keystore: GeneratedKeystore,
        assetLinksJson: String? = null,
    ): ByteArray = zip { zip ->
        zip.put("$appLabel.apk", apkBytes)
        writeKeystore(zip, appLabel, keystore)
        if (assetLinksJson != null) writeAssetLinks(zip, assetLinksJson)
    }

    fun bundleAab(
        appLabel: String,
        aabBytes: ByteArray,
        keystore: GeneratedKeystore,
    ): ByteArray = zip { zip ->
        zip.put("$appLabel.aab", aabBytes)
        writeKeystore(zip, appLabel, keystore)
        zip.put("AAB-README.txt", AAB_README.toByteArray())
    }

    fun bundlePlayPackage(
        appLabel: String,
        packageId: String,
        versionName: String,
        aabBytes: ByteArray,
        keystore: GeneratedKeystore,
        icon512: ByteArray?,
        screenshots: List<ByteArray>,
    ): ByteArray = zip { zip ->
        zip.put("$appLabel.aab", aabBytes)
        writeKeystore(zip, appLabel, keystore)
        if (icon512 != null) zip.put("store-listing/icon-512.png", icon512)
        screenshots.forEachIndexed { i, shot ->
            zip.put("store-listing/screenshots/screenshot-${(i + 1).toString().padStart(2, '0')}.png", shot)
        }
        zip.put("store-listing/listing.txt", playListingTemplate(appLabel, packageId, versionName).toByteArray())
        zip.put("PLAY-README.txt", playReadme(screenshots.size, icon512 != null).toByteArray())
    }

    // ---- helpers ----

    private class Zip(private val zos: ZipOutputStream) {
        fun put(name: String, content: ByteArray) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(content)
            zos.closeEntry()
        }
    }

    private fun zip(block: (Zip) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { block(Zip(it)) }
        return out.toByteArray()
    }

    private fun writeKeystore(zip: Zip, appLabel: String, keystore: GeneratedKeystore) {
        zip.put("$appLabel.keystore", keystore.pkcs12Bytes)
        zip.put(
            "keystore-info.txt",
            """
            alias=${keystore.alias}
            storePassword=${keystore.storePassword}
            keyPassword=${keystore.keyPassword}
            sha256CertFingerprint=${keystore.certificateSha256Fingerprint}

            Keep this file! You need these values to re-sign future updates
            of the same app with the same signing identity.
            """.trimIndent().toByteArray(),
        )
    }

    private fun writeAssetLinks(zip: Zip, assetLinksJson: String) {
        zip.put("assetlinks.json", assetLinksJson.toByteArray())
        zip.put(
            "assetlinks-README.txt",
            """
            このアプリはPWAモード(既存サイトのラップ)で作成されました。

            同梱の assetlinks.json は、あなたのサイト側の
                https://<あなたのドメイン>/.well-known/assetlinks.json
            にそのままアップロードしてください(APK自身はあなたのサーバーへ
            ファイルを配置できないため、このファイルは手動で設置する必要があります)。
            """.trimIndent().toByteArray(),
        )
    }

    private const val AAB_README =
        """このzipには署名済みの .aab (Android App Bundle) が含まれています。
Google Play Console にアップロードして提出できます。

※ .aab は端末に直接インストールはできません(Playが端末ごとのAPKを生成します)。
   手元の実機で動作確認したい場合は「APK生成」で作ったapkを使ってください。
※ 署名は同梱の keystore による自己署名(アップロード鍵)です。Play App Signing
   を使う場合はこのAABをそのままアップロードすればOKです。keystore-info.txt の
   パスワードは大切に保管してください(アプリ更新時に同じ鍵で署名する必要があります)。"""

    private fun playListingTemplate(appLabel: String, packageId: String, versionName: String): String = """
        # Google Play ストア掲載情報 (テンプレート)
        # 下記を埋めて Play Console に入力してください。

        アプリ名: $appLabel
        パッケージ名 (applicationId): $packageId
        バージョン: $versionName

        簡単な説明 (最大80文字):
        （ここに1〜2行の短い説明を書く）

        詳細な説明 (最大4000文字):
        （ここにアプリの詳しい説明を書く）

        カテゴリ: （ゲーム / ツール など）
        連絡先メール:
        プライバシーポリシーURL:

        # スクリーンショット: store-listing/screenshots/ を参照
        # アイコン(512x512): store-listing/icon-512.png
    """.trimIndent()

    private fun playReadme(screenshotCount: Int, hasIcon: Boolean): String = """
        Google Play 提出用パッケージ

        含まれるもの:
        - <アプリ名>.aab ... Play Console にアップロードするApp Bundle
        - <アプリ名>.keystore / keystore-info.txt ... 署名鍵(大切に保管)
        - store-listing/listing.txt ... ストア掲載情報の下書き
        - store-listing/icon-512.png ... ${if (hasIcon) "アプリアイコン(512px)" else "(アイコン未設定のため無し)"}
        - store-listing/screenshots/ ... スクリーンショット${screenshotCount}枚${if (screenshotCount == 0) "(未撮影)" else ""}

        Play の要件(参考):
        - スクリーンショットは最低2枚(スマホ)。アプリ内の「プレビュー/スクショ」で撮影できます。
        - アイコンは512x512 PNG。フィーチャーグラフィック(1024x500)は別途用意が必要です。
    """.trimIndent()
}
