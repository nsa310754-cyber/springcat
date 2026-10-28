package com.example.bionicdesktop

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Device Admin(デバイス管理)レシーバ — オプトイン。
 *
 * 【必要性の結論(調査済み)】
 * Device Admin API は 2017 年に非推奨化され、カメラ/キーガード/パスワード系ポリシーは
 * Android 10 以降で無効化(例外)。現行の後継は Device Owner / Profile Owner。
 * 本アプリの用途(バイナリ実行・ターミナル・デスクトップ)には【必須ではない】。
 * 深いシステム制御は root で足りる。ロック/ワイプ/パスワード強制を使いたい場合のみ有効化する。
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Device Admin 有効化", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Device Admin 無効化", Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    }
}
