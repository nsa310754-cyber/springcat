package com.apkbuilder.app

import android.content.Context
import android.net.Uri
import com.apkbuilder.core.ApkAssembler
import com.apkbuilder.core.ApkSigner
import com.apkbuilder.core.AssetLinksGenerator
import com.apkbuilder.core.BuildConfig
import com.apkbuilder.core.FirebaseConfigParser
import com.apkbuilder.core.GameObfuscator
import com.apkbuilder.core.GeneratedKeystore
import com.apkbuilder.core.KeystoreGenerator
import com.apkbuilder.core.KeystoreLoader
import com.apkbuilder.core.SigningKey
import com.apkbuilder.core.aab.AabAssembler
import com.apkbuilder.core.aab.JarSigner
import com.apkbuilder.core.pwa.PwaManifest

enum class OutputFormat { APK, AAB, PLAY_ZIP }

class GenerationParams(
    val context: Context,
    val appName: String,
    val packageId: String,
    val versionName: String,
    val versionCode: Int,
    val permissions: List<String>,
    val iconUri: Uri?,
    val gameFileUri: Uri?,
    val gameFolderUri: Uri?,
    /** When set (and not PWA), this HTML from the in-app editor is the game body, ignoring file/folder. */
    val editedHtml: String?,
    val pwaManifest: PwaManifest?,
    val pwaIconBytes: ByteArray?,
    val obfuscateGame: Boolean,
    val googleServicesUri: Uri?,
    val admobAppId: String?,
    val admobBannerUnitId: String?,
    val format: OutputFormat,
    val screenshots: List<ByteArray>,
    /** When set, sign with this existing keystore (to build an update) instead of a fresh key. */
    val existingKeystoreUri: Uri?,
    val existingKeystoreStorePassword: String,
    val existingKeystoreKeyPassword: String,
    val existingKeystoreAlias: String,
)

/**
 * Resolves all inputs (reads picked files) and produces the final zip for the
 * chosen [OutputFormat]. Runs off the main thread. Shared by every output path
 * so APK / AAB / Play-package builds all brand the app identically.
 */
object Generation {

    fun generate(p: GenerationParams): ByteArray {
        val usesServices = p.googleServicesUri != null || p.admobAppId != null

        val fileOverrides = HashMap<String, ByteArray>()
        if (p.pwaManifest != null) {
            fileOverrides["assets/game.html"] = buildPwaRedirectHtml(p.pwaManifest.startUrl).toByteArray()
        } else if (!p.editedHtml.isNullOrBlank()) {
            fileOverrides["assets/game.html"] = p.editedHtml.toByteArray()
        } else {
            fileOverrides.putAll(
                when {
                    p.gameFileUri != null -> WebBundleReader.readSingleHtmlFile(p.context.contentResolver, p.gameFileUri)
                    p.gameFolderUri != null -> WebBundleReader.readFolder(p.context, p.gameFolderUri)
                    else -> error("ゲームのHTMLが指定されていません")
                },
            )
        }

        // Resolve the launcher icon bytes (manual pick beats the auto-fetched PWA icon).
        val iconBytes: ByteArray? = when {
            p.iconUri != null -> p.context.contentResolver.openInputStream(p.iconUri)?.use { it.readBytes() }
                ?: error("アイコン画像を読み込めませんでした")
            p.pwaIconBytes != null -> p.pwaIconBytes
            else -> null
        }
        if (iconBytes != null) {
            runCatching { fileOverrides.putAll(IconResizer.buildIconOverrides(iconBytes)) }
        }

        val filesToOmit = mutableSetOf<String>()
        if (p.obfuscateGame && p.pwaManifest == null) {
            val plainHtml = fileOverrides.remove("assets/game.html")
                ?: error("game.html がありません(難読化の対象がありません)")
            fileOverrides["assets/game.enc"] = GameObfuscator.encryptGameHtml(plainHtml)
            filesToOmit.add("assets/game.html")
        }

        if (p.googleServicesUri != null) {
            val json = p.context.contentResolver.openInputStream(p.googleServicesUri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("google-services.json を読み込めませんでした")
            fileOverrides["assets/firebase-config.json"] =
                FirebaseConfigParser.toRuntimeConfig(json, p.packageId).json.toByteArray()
        }
        if (p.admobAppId != null && p.admobBannerUnitId != null) {
            fileOverrides["assets/admob-config.json"] =
                """{"bannerAdUnitId":"${p.admobBannerUnitId}"}""".toByteArray()
        }

        val config = BuildConfig(
            appLabel = p.appName,
            packageId = p.packageId,
            versionName = p.versionName,
            versionCode = p.versionCode,
            permissions = p.permissions,
            admobApplicationId = p.admobAppId,
        )
        val signing = resolveSigning(p)
        val signingKey = signing.signingKey
        val exportKeystore = signing.exportKeystore

        return when (p.format) {
            OutputFormat.APK -> {
                val template = p.context.assets.open(if (usesServices) "template-services.apk" else "template.apk")
                    .use { it.readBytes() }
                val unsigned = ApkAssembler.assemble(template, config, fileOverrides, filesToOmit)
                val signed = ApkSigner.sign(unsigned, signingKey)
                val assetLinks = p.pwaManifest?.let {
                    AssetLinksGenerator.generate(p.packageId, signingKey.certificateSha256Fingerprint)
                }
                OutputBundler.bundleApk(p.appName, signed, exportKeystore, assetLinks)
            }
            OutputFormat.AAB -> {
                val signedAab = buildSignedAab(p, config, fileOverrides, filesToOmit, usesServices, signingKey)
                OutputBundler.bundleAab(p.appName, signedAab, exportKeystore)
            }
            OutputFormat.PLAY_ZIP -> {
                val signedAab = buildSignedAab(p, config, fileOverrides, filesToOmit, usesServices, signingKey)
                val icon512 = iconBytes?.let { runCatching { IconResizer.resizeToPng(it, 512) }.getOrNull() }
                OutputBundler.bundlePlayPackage(
                    appLabel = p.appName,
                    packageId = p.packageId,
                    versionName = p.versionName,
                    aabBytes = signedAab,
                    keystore = exportKeystore,
                    icon512 = icon512,
                    screenshots = p.screenshots,
                )
            }
        }
    }

    private class SigningContext(val signingKey: SigningKey, val exportKeystore: GeneratedKeystore)

    /** Uses the caller's keystore when provided (→ a valid update), otherwise mints a fresh key. */
    private fun resolveSigning(p: GenerationParams): SigningContext {
        val uri = p.existingKeystoreUri
        if (uri != null) {
            val bytes = p.context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("keystore ファイルを読み込めませんでした")
            val storePw = p.existingKeystoreStorePassword
            val keyPw = p.existingKeystoreKeyPassword.ifBlank { storePw }
            val key = KeystoreLoader.load(bytes, storePw, keyPw, p.existingKeystoreAlias)
            val export = GeneratedKeystore(bytes, key.alias, storePw, keyPw, key.certificateSha256Fingerprint)
            return SigningContext(key, export)
        }
        val gen = KeystoreGenerator.generate(commonName = p.appName)
        return SigningContext(gen.signingKey(), gen)
    }

    private fun buildSignedAab(
        p: GenerationParams,
        config: BuildConfig,
        fileOverrides: Map<String, ByteArray>,
        filesToOmit: Set<String>,
        usesServices: Boolean,
        signingKey: SigningKey,
    ): ByteArray {
        val template = p.context.assets.open(if (usesServices) "template-services.aab" else "template.aab")
            .use { it.readBytes() }
        val unsigned = AabAssembler.assemble(template, config, fileOverrides, filesToOmit)
        return JarSigner.sign(unsigned, signingKey)
    }

    fun buildPwaRedirectHtml(startUrl: String): String = """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8">
        <meta http-equiv="refresh" content="0; url=$startUrl">
        <script>location.replace("$startUrl");</script>
        </head><body></body></html>
    """.trimIndent()
}
