package com.example.bionicdesktop

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 権限を「ダイアログ(アラート)」で付けるためのヘルパ。
 *
 * - runtime 権限  : requestPermissions() → OS の「許可しますか?」ダイアログ
 * - 特別なアクセス : requestPermissions() では付かないので、該当の設定画面(トグル)へ誘導する
 *                   (全ファイルアクセス / 他アプリの上に表示 / 不明なアプリのインストール 等)
 */
object PermissionManager {

    const val REQ_RUNTIME = 1001

    /** ダイアログで要求できる dangerous 権限。端末/SDK に無いものは実行時に自動で除外する。 */
    private fun runtimePermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return list.toTypedArray()
    }

    /** ① まとめてダイアログ要求。まだ許可されていないものだけ投げる。 */
    fun requestRuntime(activity: Activity) {
        val missing = runtimePermissions().filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), REQ_RUNTIME)
        }
    }

    /** ② 特別なアクセス(=設定画面のトグルで付ける権限)へ順に誘導する。 */
    fun openSpecialAccessSettings(activity: Activity) {
        val pkg = activity.packageName

        // 全ファイルアクセス (MANAGE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg"))
            )
            return
        }
        // 他アプリの上に表示 (SYSTEM_ALERT_WINDOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))
            )
            return
        }
        // 不明なアプリのインストール (REQUEST_INSTALL_PACKAGES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$pkg"))
            )
            return
        }
        // 電池最適化の除外
        activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** 現在の付与状況を1行サマリにして返す(UI 表示用)。 */
    fun summary(ctx: Context): String {
        fun ok(b: Boolean) = if (b) "✓" else "✗"
        val cam = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val allFiles = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        val overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
        val install = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()
        return "cam ${ok(cam)}  全ファイル ${ok(allFiles)}  重ね表示 ${ok(overlay)}  不明アプリ導入 ${ok(install)}"
    }
}
