package com.springcat.apkminstaller

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** PackageInstaller のセッションで base + split をまとめてインストールする。 */
object ApkInstaller {

    class Outcome(val status: Int, val message: String?, val packageName: String?) {
        val isSuccess: Boolean get() = status == PackageInstaller.STATUS_SUCCESS
    }

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Outcome>>()

    /** 1 パッケージ分の APK 群を 1 セッションで書き込んでコミットし、結果を待つ。 */
    suspend fun install(context: Context, apks: List<File>, label: String): Outcome {
        require(apks.isNotEmpty()) { "no apk" }
        val installer = context.packageManager.packageInstaller

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apks.sumOf { it.length() })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { params.setInstallReason(PackageManager.INSTALL_REASON_USER) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 許可される場合 (自分がインストール元のアプリの更新など) は確認ダイアログを省略できる
            runCatching {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        val deferred = CompletableDeferred<Outcome>()
        pending[sessionId] = deferred

        try {
            installer.openSession(sessionId).use { session ->
                apks.forEachIndexed { index, file ->
                    val name = "${index}_${UriUtils.sanitizeFileName(file.name)}"
                    session.openWrite(name, 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out, 256 * 1024) }
                        session.fsync(out)
                    }
                    AppLog.i("  + ${file.name} (${file.length() / 1024} KB)")
                }
                session.commit(resultSender(context, sessionId, label))
            }
        } catch (t: Throwable) {
            pending.remove(sessionId)
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }

        return deferred.await()
    }

    internal fun complete(sessionId: Int, outcome: Outcome) {
        pending.remove(sessionId)?.complete(outcome)
    }

    private fun resultSender(context: Context, sessionId: Int, label: String): android.content.IntentSender {
        val intent = Intent(context, InstallResultReceiver::class.java).apply {
            action = InstallResultReceiver.ACTION_RESULT
            setPackage(context.packageName)
            putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(InstallResultReceiver.EXTRA_LABEL, label)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    fun statusText(status: Int): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "成功"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "キャンセルされました"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "ブロックされました"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "既存アプリと競合 (署名違い/ダウングレード)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "この端末に非対応 (ABI/SDK 不一致)"
        PackageInstaller.STATUS_FAILURE_INVALID -> "APK が不正です (split の組み合わせ違いなど)"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "ストレージ不足"
        else -> "失敗 (code=$status)"
    }
}
