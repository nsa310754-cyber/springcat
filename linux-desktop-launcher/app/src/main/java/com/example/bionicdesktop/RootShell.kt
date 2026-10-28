package com.example.bionicdesktop

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * root (su) 経由でコマンドを実行する。
 *
 * ・端末が root 化済み(Magisk 等)であることが前提。APK 側が端末を root 化することはできない。
 * ・初回の `su` 実行時に Magisk 等が「許可しますか?」ポップアップを出す = これがご要望の「root をダイアログで付与」。
 */
object RootShell {

    /** su バイナリが存在し、root を取得できるか。 */
    fun isAvailable(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    /** su -c で1コマンド実行し、stdout+stderr を返す。 */
    fun exec(cmd: String): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val reader = BufferedReader(InputStreamReader(p.inputStream))
        val sb = StringBuilder()
        reader.forEachLine { sb.appendLine(it) }
        p.waitFor()
        sb.toString()
    } catch (e: Exception) {
        "su 実行失敗: ${e.message}"
    }

    /**
     * ダイアログでは付けられない signature|privileged 権限を root 経由で付与する。
     * 例: WRITE_SECURE_SETTINGS。root が無ければ adb (`adb shell pm grant ...`) で付ける旨を返す。
     */
    fun grantSecureSettings(packageName: String): String {
        if (!isAvailable()) {
            return "root 無し。PCから次を実行してください:\n" +
                "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        }
        return exec("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
    }
}
