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

    /**
     * ダイアログで要求できる dangerous 権限をほぼ全て列挙する。
     * 端末/SDK に存在しない定数は参照できないよう SDK バージョンで分岐し、未宣言のものは
     * requestRuntime 側で「マニフェスト宣言済みのものだけ」に自動で絞る。
     */
    private fun runtimePermissions(): Array<String> {
        val list = mutableListOf(
            // カメラ / マイク
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            // 位置
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            // 連絡先 / アカウント / カレンダー
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            // 電話 / 通話 / SMS
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            // センサー
            Manifest.permission.BODY_SENSORS,
            // ストレージ(〜API28)
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            list.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            list.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
            list.add(Manifest.permission.READ_MEDIA_AUDIO)
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        return list.toTypedArray()
    }

    /** マニフェストに宣言済みの権限集合。未宣言の権限を要求しても OS が無視するため絞り込む。 */
    private fun declaredPermissions(activity: Activity): Set<String> = try {
        activity.packageManager
            .getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet() ?: emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    /** ① まとめてダイアログ要求。宣言済み & 未許可のものだけを投げる。 */
    fun requestRuntime(activity: Activity) {
        val declared = declaredPermissions(activity)
        val missing = runtimePermissions().filter {
            it in declared &&
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
