package com.springcat.apkdoctor.diagnose

import androidx.annotation.StringRes
import com.springcat.apkdoctor.R

enum class Severity { BLOCKER, WARNING, INFO }

/** A repair the app can perform on its own. */
enum class RepairAction {
    SALVAGE_ZIP,
    STORE_RESOURCES_ARSC,
    LOWER_MIN_SDK,
    RAISE_TARGET_SDK,
    REMOVE_MAX_SDK,
    CLEAR_TEST_ONLY,
    CLEAR_SPLIT_REQUIRED,
    SELECT_SPLITS,
    RESIGN,
}

enum class IssueId(
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int,
    val severity: Severity,
    val fix: RepairAction?,
) {
    NOT_AN_APK(R.string.issue_not_an_apk, R.string.issue_not_an_apk_detail, Severity.BLOCKER, null),
    ZIP_CORRUPT(R.string.issue_zip_corrupt, R.string.issue_zip_corrupt_detail, Severity.BLOCKER, RepairAction.SALVAGE_ZIP),
    NO_SIGNATURE(R.string.issue_no_signature, R.string.issue_no_signature_detail, Severity.BLOCKER, RepairAction.RESIGN),
    SIGNATURE_INVALID(R.string.issue_signature_invalid, R.string.issue_signature_invalid_detail, Severity.BLOCKER, RepairAction.RESIGN),
    SPLIT_SIGNER_MISMATCH(R.string.issue_split_signer, R.string.issue_split_signer_detail, Severity.BLOCKER, RepairAction.RESIGN),
    V1_ONLY_SIGNATURE(R.string.issue_v1_only, R.string.issue_v1_only_detail, Severity.BLOCKER, RepairAction.RESIGN),
    ARSC_COMPRESSED(R.string.issue_arsc_compressed, R.string.issue_arsc_compressed_detail, Severity.BLOCKER, RepairAction.STORE_RESOURCES_ARSC),
    MIN_SDK_TOO_HIGH(R.string.issue_min_sdk, R.string.issue_min_sdk_detail, Severity.BLOCKER, RepairAction.LOWER_MIN_SDK),
    TARGET_SDK_TOO_LOW(R.string.issue_target_sdk, R.string.issue_target_sdk_detail, Severity.BLOCKER, RepairAction.RAISE_TARGET_SDK),
    MAX_SDK_TOO_LOW(R.string.issue_max_sdk, R.string.issue_max_sdk_detail, Severity.BLOCKER, RepairAction.REMOVE_MAX_SDK),
    TEST_ONLY(R.string.issue_test_only, R.string.issue_test_only_detail, Severity.BLOCKER, RepairAction.CLEAR_TEST_ONLY),
    SPLIT_REQUIRED(R.string.issue_split_required, R.string.issue_split_required_detail, Severity.BLOCKER, RepairAction.CLEAR_SPLIT_REQUIRED),
    BUNDLE_CONTAINER(R.string.issue_bundle, R.string.issue_bundle_detail, Severity.BLOCKER, RepairAction.SELECT_SPLITS),
    ABI_MISMATCH(R.string.issue_abi, R.string.issue_abi_detail, Severity.BLOCKER, null),
    NO_BASE_APK(R.string.issue_no_base, R.string.issue_no_base_detail, Severity.BLOCKER, null),
    SIGNER_CONFLICT(R.string.issue_signer_conflict, R.string.issue_signer_conflict_detail, Severity.BLOCKER, null),
    VERSION_DOWNGRADE(R.string.issue_downgrade, R.string.issue_downgrade_detail, Severity.BLOCKER, null),

    RESIGN_BREAKS_UPDATE(R.string.issue_resign_update, R.string.issue_resign_update_detail, Severity.WARNING, null),
    RAISED_TARGET_SDK_RISK(R.string.issue_target_risk, R.string.issue_target_risk_detail, Severity.WARNING, null),
    LOWERED_MIN_SDK_RISK(R.string.issue_min_risk, R.string.issue_min_risk_detail, Severity.WARNING, null),
    SHARED_USER_ID(R.string.issue_shared_uid, R.string.issue_shared_uid_detail, Severity.WARNING, null),
    UNKNOWN_SOURCES(R.string.issue_unknown_sources, R.string.issue_unknown_sources_detail, Severity.WARNING, null),

    DEBUGGABLE(R.string.issue_debuggable, R.string.issue_debuggable_detail, Severity.INFO, null),
}

data class Diagnosis(
    val id: IssueId,
    val args: List<Any> = emptyList(),
) {
    val severity: Severity get() = id.severity
    val fix: RepairAction? get() = id.fix
}

/** Everything known about the uploaded file after inspection. */
data class ApkReport(
    val displayName: String,
    val sizeBytes: Long,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int?,
    val targetSdk: Int?,
    val abis: Set<String>,
    val signatureSchemes: List<String>,
    val partCount: Int,
    val selectedPartCount: Int,
    val diagnoses: List<Diagnosis>,
) {
    val blockers: List<Diagnosis> get() = diagnoses.filter { it.severity == Severity.BLOCKER }

    val repairActions: List<RepairAction>
        get() = buildList {
            addAll(diagnoses.mapNotNull { it.fix })
            // Rebuilding a damaged archive discards its signature.
            if (any { it == RepairAction.SALVAGE_ZIP }) add(RepairAction.RESIGN)
        }.distinct()

    val isRepairable: Boolean get() = blockers.isNotEmpty() && blockers.all { it.fix != null }

    /** Nothing blocking: the file should install as-is. */
    val isInstallableAsIs: Boolean get() = blockers.isEmpty()
}
