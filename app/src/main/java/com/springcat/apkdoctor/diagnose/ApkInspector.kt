package com.springcat.apkdoctor.diagnose

import com.springcat.apkdoctor.apk.ApkBundle
import com.springcat.apkdoctor.apk.ContainerKind
import com.springcat.apkdoctor.apk.DeviceProfile

/** The already-installed app with the same package name, if there is one. */
data class InstalledApp(
    val packageName: String,
    val versionCode: Long,
    val certificateSha256: String?,
)

/** Environment facts the inspector cannot read off the APK itself. */
data class InspectionContext(
    val device: DeviceProfile,
    val installedApp: InstalledApp?,
    val canRequestInstalls: Boolean,
)

object ApkInspector {

    /**
     * The lowest `targetSdkVersion` the running platform will accept. Android 14
     * started rejecting very old targets outright, and Android 15 raised the bar.
     */
    fun minimumTargetSdk(deviceSdk: Int): Int = when {
        deviceSdk >= 35 -> 24
        deviceSdk >= 34 -> 23
        else -> 0
    }

    fun inspect(bundle: ApkBundle, context: InspectionContext, displayName: String): ApkReport {
        val device = context.device
        val diagnoses = mutableListOf<Diagnosis>()

        if (bundle.kind == ContainerKind.UNKNOWN || bundle.parts.isEmpty()) {
            return ApkReport(
                displayName = displayName,
                sizeBytes = bundle.sourceFile.length(),
                packageName = null,
                versionName = null,
                versionCode = 0,
                minSdk = null,
                targetSdk = null,
                abis = emptySet(),
                signatureSchemes = emptyList(),
                partCount = bundle.parts.size,
                selectedPartCount = 0,
                diagnoses = listOf(
                    Diagnosis(IssueId.NOT_AN_APK, listOf(bundle.error ?: "不明なエラー")),
                ),
            )
        }

        val base = bundle.base
        // A lone APK whose manifest could not be read has no "base" either, but
        // that is the corruption talking, not a missing part.
        if (base == null && bundle.isBundle) {
            diagnoses += Diagnosis(IssueId.NO_BASE_APK)
        }

        // A container of splits cannot be installed by tapping the file; it needs
        // a multi-APK install session, which is itself the repair.
        if (bundle.isBundle) {
            diagnoses += Diagnosis(
                IssueId.BUNDLE_CONTAINER,
                listOf(bundle.parts.size, bundle.selectedParts.size),
            )
        }

        val primary = base ?: bundle.parts.first().archive
        val manifest = primary.manifest

        primary.zipError?.let { diagnoses += Diagnosis(IssueId.ZIP_CORRUPT, listOf(it)) }

        if (manifest != null) {
            // --- SDK range -------------------------------------------------
            val minSdk = manifest.effectiveMinSdk
            if (minSdk > device.sdkInt) {
                diagnoses += Diagnosis(IssueId.MIN_SDK_TOO_HIGH, listOf(minSdk, device.sdkInt))
                diagnoses += Diagnosis(IssueId.LOWERED_MIN_SDK_RISK)
            }

            val requiredTarget = minimumTargetSdk(device.sdkInt)
            val targetSdk = manifest.effectiveTargetSdk
            if (requiredTarget > 0 && targetSdk < requiredTarget) {
                diagnoses += Diagnosis(
                    IssueId.TARGET_SDK_TOO_LOW,
                    listOf(targetSdk, device.sdkInt, requiredTarget),
                )
                diagnoses += Diagnosis(IssueId.RAISED_TARGET_SDK_RISK)
            }

            manifest.maxSdk?.let { maxSdk ->
                if (maxSdk < device.sdkInt) {
                    diagnoses += Diagnosis(IssueId.MAX_SDK_TOO_LOW, listOf(maxSdk, device.sdkInt))
                }
            }

            // --- Manifest flags --------------------------------------------
            if (manifest.testOnly) diagnoses += Diagnosis(IssueId.TEST_ONLY)

            // Only a problem when there are no splits to satisfy the requirement.
            if (manifest.isSplitRequired && !bundle.isBundle) {
                diagnoses += Diagnosis(IssueId.SPLIT_REQUIRED)
            }

            if (!manifest.sharedUserId.isNullOrEmpty()) {
                diagnoses += Diagnosis(IssueId.SHARED_USER_ID, listOf(manifest.sharedUserId))
            }

            if (manifest.debuggable) diagnoses += Diagnosis(IssueId.DEBUGGABLE)
        }

        // --- Packaging -----------------------------------------------------
        val targetSdkForArsc = manifest?.effectiveTargetSdk ?: 0
        if (primary.resourcesArscCompressed && targetSdkForArsc >= 30 && device.sdkInt >= 30) {
            diagnoses += Diagnosis(IssueId.ARSC_COMPRESSED)
        }

        // --- Native code ---------------------------------------------------
        val selectedAbis = bundle.selectedParts.flatMap { it.archive.abis }.toSet()
        val allAbis = bundle.parts.flatMap { it.archive.abis }.toSet()
        if (allAbis.isNotEmpty() && selectedAbis.none { it in device.abis }) {
            if (device.abis.none { it in allAbis }) {
                diagnoses += Diagnosis(
                    IssueId.ABI_MISMATCH,
                    listOf(allAbis.joinToString(", "), device.abis.joinToString(", ")),
                )
            }
        }

        // --- Signature -----------------------------------------------------
        // Every part of a session must be signed, and signed by the same key, so
        // the whole selection is checked rather than just the base.
        val signedParts = bundle.selectedParts.map { it.archive }.ifEmpty { listOf(primary) }
        val signature = primary.signature
        val effectiveTarget = manifest?.effectiveTargetSdk ?: 0
        val unsigned = signedParts.filter { !it.signature.hasAny }
        val unverified = signedParts.filter { it.signature.hasAny && !it.signature.verified }
        val fingerprints = signedParts.mapNotNull { it.signature.certificateSha256 }.distinct()

        when {
            unsigned.isNotEmpty() ->
                diagnoses += Diagnosis(
                    IssueId.NO_SIGNATURE,
                    listOf(unsigned.joinToString(", ") { it.file.name }),
                )

            unverified.isNotEmpty() ->
                diagnoses += Diagnosis(
                    IssueId.SIGNATURE_INVALID,
                    listOf(
                        unverified.first().signature.errors.take(2).joinToString(" / ")
                            .ifEmpty { "検証に失敗しました" },
                    ),
                )

            fingerprints.size > 1 ->
                diagnoses += Diagnosis(IssueId.SPLIT_SIGNER_MISMATCH, listOf(fingerprints.size))

            // Android 11+ will not install a targetSdk 30+ app that only has a
            // v1 JAR signature.
            signature.v1 && !signature.v2 && !signature.v3 &&
                effectiveTarget >= 30 && device.sdkInt >= 30 ->
                diagnoses += Diagnosis(IssueId.V1_ONLY_SIGNATURE, listOf(effectiveTarget))
        }

        // --- Conflicts with what is already installed ----------------------
        val installed = context.installedApp
        if (installed != null && manifest != null) {
            val signerChanged = signature.certificateSha256 != null &&
                installed.certificateSha256 != null &&
                signature.certificateSha256 != installed.certificateSha256
            if (signerChanged) {
                diagnoses += Diagnosis(IssueId.SIGNER_CONFLICT, listOf(installed.packageName))
            } else if (installed.versionCode > manifest.versionCode) {
                diagnoses += Diagnosis(
                    IssueId.VERSION_DOWNGRADE,
                    listOf(installed.versionCode, manifest.versionCode),
                )
            }
            // Re-signing always produces a different signer than the installed copy.
            if (!signerChanged && diagnoses.any { it.fix?.requiresResign == true }) {
                diagnoses += Diagnosis(IssueId.RESIGN_BREAKS_UPDATE, listOf(installed.packageName))
            }
        }

        if (!context.canRequestInstalls) {
            diagnoses += Diagnosis(IssueId.UNKNOWN_SOURCES)
        }

        return ApkReport(
            displayName = displayName,
            sizeBytes = bundle.sourceFile.length(),
            packageName = manifest?.packageName,
            versionName = manifest?.versionName,
            versionCode = manifest?.versionCode ?: 0,
            minSdk = manifest?.minSdk,
            targetSdk = manifest?.targetSdk,
            abis = allAbis,
            signatureSchemes = signature.schemeNames,
            partCount = bundle.parts.size,
            selectedPartCount = bundle.selectedParts.size,
            diagnoses = diagnoses.distinctBy { it.id },
        )
    }
}

/** Any repair that rewrites APK bytes invalidates the existing signature. */
val RepairAction.requiresResign: Boolean
    get() = this != RepairAction.SELECT_SPLITS
