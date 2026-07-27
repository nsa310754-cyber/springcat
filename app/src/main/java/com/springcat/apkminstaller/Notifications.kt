package com.springcat.apkminstaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {

    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_ACTION = "action"
    const val ID_INSTALL = 1001
    const val ID_WATCH = 1002
    private const val ID_CONFIRM_BASE = 2000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "インストールの進行", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTION, "確認が必要", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun progress(context: Context, title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            InstallResultReceiver.pendingFlags()
        )
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showConfirmRequest(context: Context, sessionId: Int, label: String, confirm: Intent) {
        ensureChannels(context)
        val pi = PendingIntent.getActivity(
            context, sessionId, confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ACTION)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("インストールの確認")
            .setContentText("タップして「$label」のインストールを許可")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(ID_CONFIRM_BASE + sessionId, n)
        }
    }

    fun cancelConfirm(context: Context, sessionId: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_CONFIRM_BASE + sessionId) }
    }
}
