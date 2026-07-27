package com.springcat.apkminstaller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** PackageInstaller からの結果 / 確認要求を受け取る。 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESULT) return

        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "?"
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = confirmIntent(intent)
            if (confirm == null) {
                AppLog.e("確認画面を取得できませんでした: $label")
                ApkInstaller.complete(
                    sessionId,
                    ApkInstaller.Outcome(PackageInstaller.STATUS_FAILURE, "確認 Intent が空", pkg)
                )
                return
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = runCatching { context.startActivity(confirm); true }.getOrDefault(false)
            if (started && AppState.isForeground) {
                AppLog.i("確認ダイアログを表示中: $label")
            } else {
                // バックグラウンドからは Activity を起動できないので通知経由にする
                Notifications.showConfirmRequest(context, sessionId, label, confirm)
                AppLog.i("通知からインストールを承認してください: $label")
            }
            return
        }

        Notifications.cancelConfirm(context, sessionId)
        AppLog.i("結果: $label -> ${ApkInstaller.statusText(status)}${if (message.isNullOrBlank()) "" else " / $message"}")
        ApkInstaller.complete(sessionId, ApkInstaller.Outcome(status, message, pkg))
    }

    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        const val ACTION_RESULT = "com.springcat.apkminstaller.INSTALL_RESULT"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_LABEL = "label"

        fun pendingFlags(): Int {
            var f = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) f = f or PendingIntent.FLAG_IMMUTABLE
            return f
        }
    }
}

/** Activity が前面かどうか (バックグラウンド起動制限の回避判定用)。 */
object AppState {
    @Volatile
    var isForeground: Boolean = false
}
