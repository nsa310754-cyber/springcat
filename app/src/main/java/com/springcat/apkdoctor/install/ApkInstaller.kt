package com.springcat.apkdoctor.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

/** Outcome of a package-installer session. */
sealed interface InstallOutcome {
    data object Success : InstallOutcome
    data class Failure(val status: Int, val message: String) : InstallOutcome
    /** The system is showing its own confirmation dialog. */
    data object AwaitingUserConfirmation : InstallOutcome
}

/** Session status callbacks arrive as broadcasts; this fans them out. */
object InstallEvents {
    val outcomes = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 8)
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmation)
                    InstallEvents.outcomes.tryEmit(InstallOutcome.AwaitingUserConfirmation)
                } else {
                    InstallEvents.outcomes.tryEmit(
                        InstallOutcome.Failure(status, "確認画面を開けませんでした"),
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS ->
                InstallEvents.outcomes.tryEmit(InstallOutcome.Success)

            else ->
                InstallEvents.outcomes.tryEmit(InstallOutcome.Failure(status, describe(status, message)))
        }
    }

    private fun describe(status: Int, message: String): String {
        val reason = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "インストールがキャンセルされました"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "システムによってブロックされました"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "既存アプリと競合しています（署名違い、またはダウングレード）"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "この端末と互換性がありません"
            PackageInstaller.STATUS_FAILURE_INVALID -> "APKが無効です"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "ストレージの空き容量が足りません"
            else -> "インストールに失敗しました"
        }
        return if (message.isBlank()) reason else "$reason\n$message"
    }
}

/**
 * Installs one or more APKs (a base plus its splits) through
 * [PackageInstaller], which is the only way to install split APKs.
 */
class ApkInstaller(private val context: Context) {

    fun canRequestInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * Streams [files] into a new session and commits it. Progress is reported
     * through [InstallEvents]; a base APK must come first in the list.
     */
    fun install(files: List<File>, packageName: String?) {
        require(files.isNotEmpty()) { "no APKs to install" }
        val installer = context.packageManager.packageInstaller

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        packageName?.let { params.setAppPackageName(it) }
        params.setSize(files.sumOf { it.length() })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Do not claim update ownership; that would block future updates
            // from the app's original installer.
            params.setRequestUpdateOwnership(false)
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            files.forEachIndexed { index, file ->
                // Names must be unique within a session but are otherwise free-form.
                session.openWrite("$index-${file.name}", 0, file.length()).use { output ->
                    file.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                    session.fsync(output)
                }
            }
            session.commit(statusIntentSender(sessionId))
        }
    }

    private fun statusIntentSender(sessionId: Int) = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    ).intentSender

    /** Opens the system settings page that grants "install unknown apps". */
    fun unknownSourcesSettingsIntent(): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        )

    /** Uninstall request for resolving a signature conflict. */
    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$packageName"))
}
