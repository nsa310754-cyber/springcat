package com.example.bionicdesktop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File

/**
 * Winlator 連携。
 *
 * Winlator は「外部から任意の .exe を直接起動する」公開インテントを持たないため、
 * 本ブリッジは現実的に次を行う:
 *   1) 選んだファイルを共有フォルダ(Download/BionicDesktop)へコピー
 *   2) 端末に Winlator があれば起動(その後ユーザが drive_c へ入れてショートカット作成)
 *   3) 無ければ Winlator 入手ページをブラウザで開く
 */
object WinlatorBridge {

    // 代表的な Winlator 系パッケージ名(公式/フォーク)
    private val KNOWN = listOf(
        "com.winlator",
        "com.winlator.cmod",
        "com.winlator.bionic",
        "com.winlator.ludashi",
    )

    private const val RELEASES_URL = "https://github.com/winebox64/winlator/releases"

    /** インストール済みの Winlator 系パッケージ名を返す(無ければ null)。 */
    fun installedPackage(ctx: Context): String? {
        val pm = ctx.packageManager
        // 既知名を優先
        KNOWN.forEach { if (pm.getLaunchIntentForPackage(it) != null) return it }
        // 名前に winlator を含むものを総当り(QUERY_ALL_PACKAGES 使用)
        return try {
            pm.getInstalledPackages(0).firstOrNull {
                it.packageName.contains("winlator", ignoreCase = true) &&
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }?.packageName
        } catch (e: Exception) {
            null
        }
    }

    /** 共有フォルダ(Download/BionicDesktop)。 */
    private fun sharedDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BionicDesktop")
            .apply { mkdirs() }

    /** 選んだファイルを共有フォルダへコピーし、Winlator 起動 or 入手ページを開く。 */
    fun handle(ctx: Context, uri: Uri, onLog: (String) -> Unit) {
        val name = queryName(ctx, uri)
        val dst = File(sharedDir(), name)
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { input.copyTo(it) }
            }
            onLog("共有フォルダへコピー: ${dst.absolutePath}")
        } catch (e: Exception) {
            onLog("コピー失敗(ストレージ権限を確認): ${e.message}")
        }

        val pkg = installedPackage(ctx)
        if (pkg != null) {
            onLog("Winlator を起動: $pkg")
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(it)
            }
            onLog(
                "次の手順で起動してください:\n" +
                    " 1) Winlator でコンテナを開く\n" +
                    " 2) ${dst.name} を drive_c(または共有フォルダ)へ配置\n" +
                    " 3) その .exe のショートコットを作成 → タップで起動"
            )
        } else {
            onLog("Winlator 未インストール → 入手ページを開きます")
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun queryName(ctx: Context, uri: Uri): String {
        var name = "program.exe"
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        } catch (_: Exception) {}
        return name.substringAfterLast('/').ifBlank { "program.exe" }
    }
}
