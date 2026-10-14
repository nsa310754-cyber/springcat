package com.springcat.apkdoctor.repair

import com.springcat.apkdoctor.apk.ApkArchive
import com.springcat.apkdoctor.apk.ApkBundle
import com.springcat.apkdoctor.apk.Arsc
import com.springcat.apkdoctor.apk.ArscException
import com.springcat.apkdoctor.apk.Axml
import com.springcat.apkdoctor.apk.AxmlDocument
import com.springcat.apkdoctor.apk.Zips
import java.io.File

data class MergeResult(
    val file: File,
    val packageName: String?,
    val mergedSplits: List<String>,
    val resourcesMerged: Boolean,
    val signerFingerprint: String,
)

class MergeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Flattens a `base.apk` and its split APKs into one standalone, universal APK —
 * every split's native libraries, resources, and assets folded into the base so
 * the result installs on its own, with no split session.
 *
 * All provided splits are included regardless of the current device, so the
 * output is a true "everything" APK. Native-library (ABI) and asset splits fold
 * in as a ZIP union; density/language splits also need their `resources.arsc`
 * configurations merged into the base table (see [Arsc]). If that resource merge
 * fails the base table is kept and the omission is reported, so the result still
 * installs and runs — it just falls back to the base's own resource configs.
 */
class ApkMerger(private val signingIdentity: () -> SigningIdentity) {

    fun merge(
        bundle: ApkBundle,
        workDir: File,
        outputDir: File,
        log: (String) -> Unit,
    ): MergeResult {
        workDir.mkdirs()
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val readable = bundle.parts.map { it.archive }.filter { it.isReadable }
        val base = readable.firstOrNull { it.manifest?.isSplit == false }
            ?: throw MergeException("base.apk が見つかりません（分割のベースが必要です）")
        val packageName = base.manifest?.packageName
        val splits = readable.filter { it !== base && it.manifest?.isSplit == true && it.manifest?.packageName == packageName }

        if (splits.isEmpty()) throw MergeException("結合する分割APKがありません")
        log("ベース: ${base.file.name}（$packageName）")
        for (split in splits) log("  + ${split.file.name}（${split.manifest?.splitName ?: "?"}）")

        // 1. Strip split markers from the base manifest so it stands alone.
        val document = base.document ?: throw MergeException("ベースAPKのマニフェストを読めません")
        stripSplitMarkers(document, log)
        val manifestBytes = document.toByteArray()

        // 2. Merge resources.arsc (density/language). Best-effort.
        val replacements = linkedMapOf<String, ByteArray>("AndroidManifest.xml" to manifestBytes)
        val resourcesMerged = mergeResources(base, splits, log)?.let {
            replacements["resources.arsc"] = it
            true
        } ?: false

        // 3. Union every archive's payload into one APK.
        val mergedUnsigned = File(workDir, "merged-unsigned.apk")
        Zips.mergeApks(
            base = base.file,
            splits = splits.map { it.file },
            dest = mergedUnsigned,
            replacements = replacements,
            drop = { Zips.isSignatureEntry(it) },
            // Uncompressed .so keeps extractNativeLibs=false APKs valid; arsc must
            // be stored on Android 11+.
            forceStored = { it == "resources.arsc" || it.endsWith(".so") },
        )

        // 4. Sign the single APK.
        val identity = signingIdentity()
        val output = File(outputDir, "${packageName ?: "merged"}-universal.apk")
        val minSdk = base.manifest?.effectiveMinSdk ?: 1
        log("v1/v2/v3 で署名しています…")
        try {
            ApkResigner.sign(mergedUnsigned, output, identity, minSdk)
        } catch (e: Exception) {
            throw MergeException("署名に失敗しました（${e.message}）", e)
        }

        log("結合APKを生成しました: ${output.name}")
        return MergeResult(
            file = output,
            packageName = packageName,
            mergedSplits = splits.map { it.manifest?.splitName ?: it.file.name },
            resourcesMerged = resourcesMerged,
            signerFingerprint = identity.fingerprintSha256,
        )
    }

    /** Returns the merged `resources.arsc`, or null when the base table is kept. */
    private fun mergeResources(base: ApkArchive, splits: List<ApkArchive>, log: (String) -> Unit): ByteArray? {
        val baseArsc = Zips.readEntry(base.file, "resources.arsc") ?: return null
        val splitArscs = splits.mapNotNull { Zips.readEntry(it.file, "resources.arsc") }
        if (splitArscs.isEmpty()) return null // ABI/asset-only splits add no resources

        return try {
            val merged = Arsc.merge(baseArsc, splitArscs)
            log("resources.arsc を統合しました（${splitArscs.size} 分割）")
            merged
        } catch (e: ArscException) {
            log("※ resources.arsc の統合をスキップしました（${e.message}）。密度/言語別リソースは base のみになります")
            null
        }
    }

    private fun stripSplitMarkers(document: AxmlDocument, log: (String) -> Unit) {
        val manifest = document.element("manifest") ?: throw MergeException("<manifest> がありません")

        var changed = false
        if (manifest.attributes.removeAll { it.uri == null && it.name == "split" }) changed = true
        if (manifest.removeAttr(Axml.Attr.IS_SPLIT_REQUIRED)) changed = true
        if (manifest.removeAttr(Axml.Attr.REQUIRED_SPLIT_TYPES)) changed = true
        if (manifest.removeAttr(Axml.Attr.SPLIT_TYPES)) changed = true
        if (manifest.attributes.removeAll { it.isAndroid && it.name == "isFeatureSplit" }) changed = true

        // The store's split bookkeeping metadata would make the installer expect
        // splits that no longer exist as separate files.
        val splitMeta = document.elements("meta-data").filter { meta ->
            meta.attr(Axml.Attr.NAME)?.asString()?.let { name ->
                name.startsWith("com.android.vending.splits") || name.startsWith("com.android.stamp")
            } ?: false
        }
        for (meta in splitMeta) {
            document.removeElement(meta)
            changed = true
        }

        if (changed) log("マニフェストから分割マーカーを除去しました")
    }
}
