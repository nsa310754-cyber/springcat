package com.example.bionicdesktop

import android.content.Context
import java.io.File

/**
 * Winlator 相当の「Windows .exe を Box64 + Wine + X サーバで起動する」オーケストレーション。
 *
 * ★ 重要:ここは「コードだけ」。巨大バイナリ(Box64/Wine/Xサーバ/DXVK)は APK に同梱しない
 *   (サイズを抑えるため)。ユーザが下記パスへ配置すれば本コードが起動する。
 *
 * 期待する配置(すべて glibc ツリー内。arm64):
 *   files/glibc/bin/box64                 … x86/x64 → ARM 変換器(ARM glibc ELF)
 *   files/glibc/opt/wine/bin/wine         … Wine 本体(x86。box64 経由で動く)
 *   files/glibc/bin/Xwrapper (任意)       … X サーバ起動スクリプト/実行体(例: Xvnc, termux-x11)
 *   files/glibc/lib, files/glibc/opt/wine/lib … それぞれの共有ライブラリ
 *
 * 起動手順(Winlator と同じ考え方):
 *   1) 初回に wineprefix を作成(wineboot --init)
 *   2) X サーバを常駐起動(DISPLAY=:0)
 *   3) box64 wine explorer /desktop=shell,WxH <exe> で起動
 *   4) グラフィックは DXVK(Direct3D→Vulkan)+ Turnip(Adreno Vulkan)を DLL override で使用
 */
object WineLauncher {

    data class Config(
        val screen: String = "1280x720",
        val dxvk: Boolean = true,          // D3D10/11 → Vulkan
        val display: String = ":0",
        val esync: Boolean = true,
    )

    // ---- 期待パス ----
    private fun box64(ctx: Context) = File(GlibcRuntime.binDir(ctx), "box64")
    private fun wine(ctx: Context) = File(GlibcRuntime.root(ctx), "opt/wine/bin/wine")
    private fun xserver(ctx: Context) = File(GlibcRuntime.binDir(ctx), "Xwrapper")
    private fun prefix(ctx: Context) = File(ctx.filesDir, "wineprefix")
    private fun wineLibDir(ctx: Context) = File(GlibcRuntime.root(ctx), "opt/wine/lib")

    private var xProc: Process? = null

    /** Box64 と Wine が配置済みか。 */
    fun isReady(ctx: Context): Boolean =
        GlibcRuntime.isInstalled(ctx) && box64(ctx).exists() && wine(ctx).exists()

    /** 不足している要素を列挙(UI 表示用)。 */
    fun missing(ctx: Context): List<String> = buildList {
        if (!GlibcRuntime.isInstalled(ctx)) add("glibc ランタイム(未展開)")
        if (!box64(ctx).exists()) add("Box64 → ${box64(ctx).absolutePath}")
        if (!wine(ctx).exists()) add("Wine → ${wine(ctx).absolutePath}")
        if (!xserver(ctx).exists()) add("X サーバ(任意) → ${xserver(ctx).absolutePath}")
    }

    /** Box64/Wine/DXVK 用の環境変数(Winlator の既定に近い構成)。 */
    private fun env(ctx: Context, cfg: Config): Map<String, String> {
        val glibcLib = GlibcRuntime.libDir(ctx).absolutePath
        val wineLib = wineLibDir(ctx).absolutePath
        val m = linkedMapOf(
            "HOME" to ctx.filesDir.absolutePath,
            "WINEPREFIX" to prefix(ctx).absolutePath,
            "DISPLAY" to cfg.display,
            "WINEDEBUG" to "-all",
            "LD_LIBRARY_PATH" to "$wineLib:$glibcLib",
            "PATH" to "${GlibcRuntime.binDir(ctx).absolutePath}:${File(GlibcRuntime.root(ctx), "opt/wine/bin").absolutePath}",
            // Box64 チューニング(動的再コンパイラ)
            "BOX64_DYNAREC" to "1",
            "BOX64_DYNAREC_BIGBLOCK" to "1",
            "BOX64_DYNAREC_STRONGMEM" to "1",
            "BOX64_DYNAREC_FASTNAN" to "1",
            "BOX64_NOBANNER" to "1",
            "BOX64_LD_LIBRARY_PATH" to wineLib,
        )
        if (cfg.esync) m["WINEESYNC"] = "1"
        if (cfg.dxvk) {
            // DXVK を使う: 対応 DLL をネイティブ(=DXVK)優先に
            m["WINEDLLOVERRIDES"] = "d3d9,d3d10core,d3d11,dxgi=n"
            // Turnip(Adreno)を Vulkan ドライバに。端末により調整。
            m["MESA_VK_WSI_PRESENT_MODE"] = "mailbox"
            m["GALLIUM_DRIVER"] = "zink"
        }
        return m
    }

    /** 初回: wineprefix を作成する(wineboot --init)。 */
    fun initPrefix(ctx: Context, cfg: Config = Config(), onLog: (String) -> Unit): Boolean {
        if (!isReady(ctx)) { onLog("Box64/Wine 未配置:\n - " + missing(ctx).joinToString("\n - ")); return false }
        prefix(ctx).mkdirs()
        onLog("wineprefix 初期化: ${prefix(ctx).absolutePath}")
        val out = GlibcRuntime.runViaLoader(
            ctx, box64(ctx).absolutePath,
            listOf(wine(ctx).absolutePath, "wineboot", "--init"),
            env(ctx, cfg),
        )
        onLog(out.ifBlank { "(出力なし)" })
        return true
    }

    /** X サーバを常駐起動する(配置されていれば)。無ければスキップ(外部 X を使う想定)。 */
    fun startX(ctx: Context, cfg: Config = Config(), onLog: (String) -> Unit) {
        if (xProc?.isAlive == true) { onLog("X サーバは起動済み"); return }
        val x = xserver(ctx)
        if (!x.exists()) { onLog("X サーバ未配置(${x.name})。外部 X / termux-x11 を DISPLAY=${cfg.display} で用意してください"); return }
        onLog("X サーバ起動: ${x.name} (${cfg.screen})")
        xProc = NativeExecutor.runDetached(
            ctx, x.absolutePath,
            listOf(cfg.display, "-screen", cfg.screen),
            mapOf("LD_LIBRARY_PATH" to GlibcRuntime.libDir(ctx).absolutePath),
        )
    }

    fun stopX(onLog: (String) -> Unit) {
        xProc?.let { it.destroy(); onLog("X サーバ停止") }
        xProc = null
    }

    /**
     * .exe を起動する。Winlator と同様 explorer の仮想デスクトップ上で動かす。
     * @return プロセス出力(GUI は X サーバ側に描画される)
     */
    fun launchExe(ctx: Context, exe: File, cfg: Config = Config(), onLog: (String) -> Unit): String {
        if (!isReady(ctx)) {
            val msg = "Box64/Wine 未配置のため起動できません:\n - " + missing(ctx).joinToString("\n - ")
            onLog(msg); return msg
        }
        if (!prefix(ctx).exists()) initPrefix(ctx, cfg, onLog)
        startX(ctx, cfg, onLog)
        onLog("起動: box64 wine explorer /desktop=shell,${cfg.screen} ${exe.name}")
        return GlibcRuntime.runViaLoader(
            ctx, box64(ctx).absolutePath,
            listOf(
                wine(ctx).absolutePath,
                "explorer", "/desktop=shell,${cfg.screen}",
                exe.absolutePath,
            ),
            env(ctx, cfg),
        )
    }
}
