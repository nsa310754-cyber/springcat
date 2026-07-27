package com.springcat.apkdoctor.repair

import com.springcat.apkdoctor.apk.ApkArchive
import com.springcat.apkdoctor.apk.ApkBundle
import com.springcat.apkdoctor.apk.Axml
import com.springcat.apkdoctor.apk.AxmlDocument
import com.springcat.apkdoctor.apk.AxmlElement
import com.springcat.apkdoctor.apk.DeviceProfile
import com.springcat.apkdoctor.apk.Zips
import com.springcat.apkdoctor.diagnose.ApkInspector
import com.springcat.apkdoctor.diagnose.RepairAction
import com.springcat.apkdoctor.diagnose.requiresResign
import java.io.File

data class RepairResult(
    /** Installable APKs, base first. */
    val files: List<File>,
    val log: List<String>,
    val resigned: Boolean,
    val signerFingerprint: String?,
)

class RepairException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Applies a set of [RepairAction]s to an uploaded APK or bundle and writes the
 * installable result into an output directory.
 */
class ApkRepairer(private val signingIdentity: () -> SigningIdentity) {

    fun repair(
        bundle: ApkBundle,
        actions: Collection<RepairAction>,
        device: DeviceProfile,
        workDir: File,
        outputDir: File,
        log: (String) -> Unit,
    ): RepairResult {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        workDir.mkdirs()

        val parts = bundle.selectedParts.sortedBy { it.archive.manifest?.isSplit == true }
        if (parts.isEmpty()) throw RepairException("インストール対象のAPKがありません")

        if (bundle.isBundle) {
            log("バンドルから ${parts.size} 個のAPKを選択しました")
            for (part in parts) log("  ・${part.archive.file.name} — ${part.reason}")
        }

        // Splits must all carry the same signature, so re-signing is decided for
        // the whole bundle rather than per part.
        val resign = actions.any { it.requiresResign }
        val identity = if (resign) {
            log("署名鍵を準備しています…")
            signingIdentity()
        } else {
            null
        }

        val outputs = mutableListOf<File>()
        for (part in parts) {
            outputs += repairPart(
                archive = part.archive,
                actions = actions,
                device = device,
                workDir = workDir,
                outputDir = outputDir,
                identity = identity,
                log = log,
            )
        }

        return RepairResult(
            files = outputs,
            log = emptyList(),
            resigned = resign,
            signerFingerprint = identity?.fingerprintSha256,
        )
    }

    private fun repairPart(
        archive: ApkArchive,
        actions: Collection<RepairAction>,
        device: DeviceProfile,
        workDir: File,
        outputDir: File,
        identity: SigningIdentity?,
        log: (String) -> Unit,
    ): File {
        val name = archive.file.name
        var current = archive.file
        var source = archive

        // --- 1. Recover a damaged archive ---------------------------------
        if (RepairAction.SALVAGE_ZIP in actions && !archive.isReadable) {
            val salvaged = File(workDir, "salvaged-$name")
            log("$name: ZIPを再構築しています…")
            val recovered = Zips.salvage(archive.file, salvaged) { !Zips.isSignatureEntry(it) }
            if (recovered == 0) throw RepairException("$name: 復旧できるエントリがありませんでした")
            log("$name: $recovered 件のエントリを復旧しました")
            current = salvaged
            source = ApkArchive.read(salvaged, verifySignature = false)
            if (!source.isReadable) {
                throw RepairException("$name: 復旧後もAPKとして読み取れません（${source.zipError}）")
            }
        }

        val document = source.document
            ?: throw RepairException("$name: AndroidManifest.xml を読み取れません")

        // --- 2. Rewrite the manifest --------------------------------------
        val manifestChanges = patchManifest(document, actions, device, name, log)
        val storeArsc = RepairAction.STORE_RESOURCES_ARSC in actions && source.resourcesArscCompressed

        if (manifestChanges > 0 || storeArsc) {
            val repacked = File(workDir, "repacked-$name")
            if (storeArsc) log("$name: resources.arsc を無圧縮で格納し直します")
            Zips.repack(
                source = current,
                dest = repacked,
                replacements = mapOf("AndroidManifest.xml" to document.toByteArray()),
                keep = { !Zips.isSignatureEntry(it) },
                forceStored = { entry -> storeArsc && Zips.mustStayUncompressed(entry) },
            )
            current = repacked
        }

        // --- 3. Sign -------------------------------------------------------
        val output = File(outputDir, name)
        if (identity != null) {
            // The signer must advertise the *patched* minSdk so apksig picks
            // digests the target platforms accept.
            val minSdk = ApkArchive.read(current, verifySignature = false)
                .manifest?.effectiveMinSdk ?: 1
            log("$name: v1/v2/v3 で署名しています…")
            try {
                ApkResigner.sign(current, output, identity, minSdk)
            } catch (e: Exception) {
                throw RepairException("$name: 署名に失敗しました（${e.message}）", e)
            }
        } else {
            current.copyTo(output, overwrite = true)
        }
        return output
    }

    /** Returns how many manifest edits were applied. */
    private fun patchManifest(
        document: AxmlDocument,
        actions: Collection<RepairAction>,
        device: DeviceProfile,
        name: String,
        log: (String) -> Unit,
    ): Int {
        var changes = 0
        val manifest = document.element("manifest")
            ?: throw RepairException("$name: <manifest> 要素がありません")

        if (RepairAction.LOWER_MIN_SDK in actions) {
            val usesSdk = requireUsesSdk(document, manifest)
            val attribute = usesSdk.requireAndroidAttr(Axml.Attr.MIN_SDK_VERSION, "minSdkVersion")
            val before = attribute.asInt()
            if (before == null || before > device.sdkInt) {
                attribute.setInt(device.sdkInt)
                log("$name: minSdkVersion ${before ?: "未指定"} → ${device.sdkInt}")
                changes++
            }
        }

        if (RepairAction.RAISE_TARGET_SDK in actions) {
            val required = ApkInspector.minimumTargetSdk(device.sdkInt)
            if (required > 0) {
                val usesSdk = requireUsesSdk(document, manifest)
                val attribute = usesSdk.requireAndroidAttr(Axml.Attr.TARGET_SDK_VERSION, "targetSdkVersion")
                val before = attribute.asInt()
                if (before == null || before < required) {
                    attribute.setInt(required)
                    log("$name: targetSdkVersion ${before ?: "未指定"} → $required")
                    changes++
                }
            }
        }

        if (RepairAction.REMOVE_MAX_SDK in actions) {
            document.element("uses-sdk")?.let { usesSdk ->
                if (usesSdk.removeAttr(Axml.Attr.MAX_SDK_VERSION)) {
                    log("$name: maxSdkVersion を削除しました")
                    changes++
                }
            }
        }

        if (RepairAction.CLEAR_TEST_ONLY in actions) {
            document.element("application")?.let { application ->
                if (application.removeAttr(Axml.Attr.TEST_ONLY)) {
                    log("$name: testOnly フラグを解除しました")
                    changes++
                }
            }
        }

        if (RepairAction.CLEAR_SPLIT_REQUIRED in actions) {
            if (manifest.removeAttr(Axml.Attr.IS_SPLIT_REQUIRED)) {
                log("$name: isSplitRequired を解除しました")
                changes++
            }
            if (manifest.removeAttr(Axml.Attr.REQUIRED_SPLIT_TYPES)) {
                log("$name: requiredSplitTypes を削除しました")
                changes++
            }
        }

        return changes
    }

    /** Old APKs may omit `<uses-sdk>` entirely; create it when a fix needs it. */
    private fun requireUsesSdk(document: AxmlDocument, manifest: AxmlElement): AxmlElement =
        document.element("uses-sdk") ?: AxmlElement(
            uri = null,
            name = "uses-sdk",
            attributes = mutableListOf(),
            line = manifest.line,
        ).also { document.insertChildFirst(manifest, it) }
}
