package com.springcat.apkdoctor.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.springcat.apkdoctor.apk.ApkBundle
import com.springcat.apkdoctor.apk.DeviceProfile
import com.springcat.apkdoctor.apk.Digests
import com.springcat.apkdoctor.diagnose.ApkInspector
import com.springcat.apkdoctor.diagnose.ApkReport
import com.springcat.apkdoctor.diagnose.InspectionContext
import com.springcat.apkdoctor.diagnose.InstalledApp
import com.springcat.apkdoctor.diagnose.IssueId
import com.springcat.apkdoctor.install.ApkInstaller
import com.springcat.apkdoctor.install.InstallEvents
import com.springcat.apkdoctor.install.InstallOutcome
import com.springcat.apkdoctor.repair.ApkRepairer
import com.springcat.apkdoctor.repair.RepairResult
import com.springcat.apkdoctor.repair.SigningKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class Phase { IDLE, ANALYZING, ANALYZED, REPAIRING, REPAIRED, INSTALLING }

data class MainUiState(
    val phase: Phase = Phase.IDLE,
    val statusText: String = "",
    val log: List<String> = emptyList(),
    val report: ApkReport? = null,
    val repaired: RepairResult? = null,
    val message: String? = null,
    val error: String? = null,
    /** Set when an already-installed package blocks the install. */
    val conflictingPackage: String? = null,
    val needsUnknownSources: Boolean = false,
) {
    val busy: Boolean
        get() = phase == Phase.ANALYZING || phase == Phase.REPAIRING || phase == Phase.INSTALLING
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()
    private val installer = ApkInstaller(context)
    private val repairer = ApkRepairer(SigningKeys(context)::loadOrCreate)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    /** Kept between analysis and repair so the file is only unpacked once. */
    private var bundle: ApkBundle? = null

    private val workDir get() = File(context.cacheDir, "work")
    private val outputDir get() = File(context.cacheDir, "output")

    init {
        viewModelScope.launch {
            InstallEvents.outcomes.collect { outcome -> onInstallOutcome(outcome) }
        }
    }

    fun onFileSelected(uri: Uri) {
        viewModelScope.launch {
            _state.value = MainUiState(phase = Phase.ANALYZING, statusText = "ファイルを読み込んでいます…")
            runCatching {
                withContext(Dispatchers.IO) { analyze(uri) }
            }.onSuccess { report ->
                _state.update {
                    it.copy(
                        phase = Phase.ANALYZED,
                        statusText = "",
                        report = report,
                        needsUnknownSources = report.diagnoses.any { d -> d.id == IssueId.UNKNOWN_SOURCES },
                        conflictingPackage = report.diagnoses
                            .firstOrNull { d -> d.id == IssueId.SIGNER_CONFLICT || d.id == IssueId.VERSION_DOWNGRADE }
                            ?.let { report.packageName },
                    )
                }
            }.onFailure { error ->
                _state.value = MainUiState(error = error.message ?: "読み込みに失敗しました")
            }
        }
    }

    private fun analyze(uri: Uri): ApkReport {
        workDir.deleteRecursively()
        outputDir.deleteRecursively()
        workDir.mkdirs()

        val displayName = queryDisplayName(uri) ?: "upload.apk"
        val local = File(workDir, sanitize(displayName))
        context.contentResolver.openInputStream(uri)?.use { input ->
            local.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("ファイルを開けませんでした")

        val device = deviceProfile()
        val opened = ApkBundle.open(local, File(workDir, "parts"), device)
        bundle = opened

        val report = ApkInspector.inspect(
            bundle = opened,
            context = InspectionContext(
                device = device,
                installedApp = opened.base?.manifest?.packageName?.let { installedApp(it) },
                canRequestInstalls = installer.canRequestInstalls(),
            ),
            displayName = displayName,
        )
        return report
    }

    fun repair() {
        val current = bundle ?: return
        val report = _state.value.report ?: return
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.REPAIRING, statusText = "修復しています…", log = emptyList(), error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    repairer.repair(
                        bundle = current,
                        actions = report.repairActions,
                        device = deviceProfile(),
                        workDir = File(workDir, "repair"),
                        outputDir = outputDir,
                        log = { line -> appendLog(line) },
                    )
                }
            }.onSuccess { result ->
                appendLog("完了: ${result.files.size} 個のAPKを生成しました")
                // Re-run the same checks against the output, so the result shown
                // describes the repaired file rather than the uploaded one.
                val rechecked = withContext(Dispatchers.IO) { recheck(result) }
                _state.update {
                    it.copy(
                        phase = Phase.REPAIRED,
                        statusText = "",
                        repaired = result,
                        report = rechecked ?: it.report,
                        message = null,
                    )
                }
                when {
                    rechecked == null -> Unit
                    rechecked.blockers.isEmpty() -> appendLog("再チェック: 問題は残っていません")
                    else -> appendLog("再チェック: 未解決の問題が ${rechecked.blockers.size} 件あります")
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(phase = Phase.ANALYZED, statusText = "", error = error.message ?: "修復に失敗しました")
                }
            }
        }
    }

    /**
     * Re-inspects a single repaired APK. Multi-part results are skipped: their
     * splits only make sense together, which the inspector cannot see from one file.
     */
    private fun recheck(result: RepairResult): ApkReport? {
        val single = result.files.singleOrNull() ?: return null
        return runCatching {
            val device = deviceProfile()
            val reopened = ApkBundle.open(single, File(workDir, "recheck"), device)
            bundle = reopened
            ApkInspector.inspect(
                bundle = reopened,
                context = InspectionContext(
                    device = device,
                    installedApp = reopened.base?.manifest?.packageName?.let { installedApp(it) },
                    canRequestInstalls = installer.canRequestInstalls(),
                ),
                displayName = _state.value.report?.displayName ?: single.name,
            )
        }.getOrNull()
    }

    fun install() {
        val state = _state.value
        val files = state.repaired?.files
            // Nothing needed fixing: install the file as it arrived.
            ?: bundle?.selectedParts?.map { it.archive.file }
            ?: return

        if (!installer.canRequestInstalls()) {
            _state.update { it.copy(needsUnknownSources = true, error = "「提供元不明のアプリ」の許可が必要です") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.INSTALLING, statusText = "インストールしています…", error = null, message = null) }
            runCatching {
                withContext(Dispatchers.IO) { installer.install(files, state.report?.packageName) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        phase = if (state.repaired != null) Phase.REPAIRED else Phase.ANALYZED,
                        statusText = "",
                        error = error.message ?: "インストールを開始できませんでした",
                    )
                }
            }
        }
    }

    private fun onInstallOutcome(outcome: InstallOutcome) {
        val fallback = if (_state.value.repaired != null) Phase.REPAIRED else Phase.ANALYZED
        when (outcome) {
            InstallOutcome.AwaitingUserConfirmation ->
                _state.update { it.copy(statusText = "システムの確認画面で「インストール」を選んでください") }

            InstallOutcome.Success ->
                _state.update {
                    it.copy(phase = fallback, statusText = "", message = "インストールが完了しました", error = null)
                }

            is InstallOutcome.Failure ->
                _state.update { it.copy(phase = fallback, statusText = "", error = outcome.message) }
        }
    }

    /** Exports the repaired APKs; several parts are written out as one ZIP. */
    fun save(target: Uri) {
        val files = _state.value.repaired?.files ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target)?.use { output ->
                        if (files.size == 1) {
                            files.first().inputStream().use { it.copyTo(output) }
                        } else {
                            ZipOutputStream(output).use { zip ->
                                for (file in files) {
                                    zip.putNextEntry(ZipEntry(file.name))
                                    file.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        }
                    } ?: throw IllegalStateException("保存先を開けませんでした")
                }
            }.onSuccess {
                _state.update { it.copy(message = "保存しました", error = null) }
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "保存に失敗しました") }
            }
        }
    }

    /** Suggested file name for the export dialog. */
    fun suggestedFileName(): String {
        val state = _state.value
        val base = (state.report?.packageName ?: state.report?.displayName?.substringBeforeLast('.') ?: "repaired")
        val multiple = (state.repaired?.files?.size ?: 1) > 1
        return if (multiple) "$base-fixed.zip" else "$base-fixed.apk"
    }

    fun uninstallIntent(packageName: String) = installer.uninstallIntent(packageName)

    fun unknownSourcesIntent() = installer.unknownSourcesSettingsIntent()

    fun dismissMessages() {
        _state.update { it.copy(error = null, message = null) }
    }

    fun reset() {
        bundle = null
        viewModelScope.launch(Dispatchers.IO) {
            workDir.deleteRecursively()
            outputDir.deleteRecursively()
        }
        _state.value = MainUiState()
    }

    fun refreshInstallPermission() {
        _state.update { it.copy(needsUnknownSources = !installer.canRequestInstalls()) }
    }

    private fun appendLog(line: String) {
        _state.update { it.copy(log = it.log + line) }
    }

    private fun deviceProfile() = DeviceProfile(
        sdkInt = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
        densityDpi = context.resources.displayMetrics.densityDpi,
        languages = buildList {
            val locales = context.resources.configuration.locales
            for (i in 0 until locales.size()) add(locales.get(i).language)
        }.distinct(),
    )

    private fun installedApp(packageName: String): InstalledApp? = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(packageName, flags)
        @Suppress("DEPRECATION")
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            info.signatures?.firstOrNull()
        }
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        InstalledApp(
            packageName = packageName,
            versionCode = versionCode,
            certificateSha256 = certificate?.toByteArray()?.let { Digests.sha256(it) },
        )
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Keeps a user-supplied name from escaping the cache directory. */
    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "upload.apk" }
}
