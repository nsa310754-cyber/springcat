package com.example.bionicdesktop

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * 「ファイルを選んで実行(拡張子不問)」の中核。
 * SAF で選ばれた content Uri を作業領域へコピーし、マジックバイトで種別判定して処理する。
 *   ELF  → 実行(glibc 依存なら内蔵ローダ経由、そうでなければ Bionic で直接)
 *   gzip → 展開(中身が tar なら untar、そうでなければ復元ファイルを保存)
 *   tar  → 展開
 *   deb  → ar 解体 → data.tar(gz/plain)展開
 *   zip 等 → 種別のみ報告(必要なら拡張)
 */
object FileRunner {

    fun workDir(ctx: Context): File = File(ctx.filesDir, "work").apply { mkdirs() }

    /** SAF Uri を処理。経過は onLog に流す。 */
    fun handle(ctx: Context, uri: Uri, onLog: (String) -> Unit) {
        val name = queryName(ctx, uri)
        val dst = File(workDir(ctx), name)
        onLog("コピー中: $name")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { input.copyTo(it) }
            } ?: run { onLog("読み込み失敗(Uri を開けません)"); return }
        } catch (e: Exception) {
            onLog("コピー失敗: ${e.message}"); return
        }
        onLog("保存: ${dst.absolutePath}  (${dst.length()} bytes)")
        dispatch(ctx, dst, onLog)
    }

    private fun dispatch(ctx: Context, file: File, onLog: (String) -> Unit) {
        when (Archive.detect(file)) {
            Archive.Kind.ELF -> runElf(ctx, file, onLog)
            Archive.Kind.PE -> runPe(ctx, file, onLog)
            Archive.Kind.GZIP -> decompressThenMaybeUntar(ctx, file, onLog, "gzip") { Archive.gunzip(it) }
            Archive.Kind.XZ -> decompressThenMaybeUntar(ctx, file, onLog, "xz") { Archive.unxz(it) }
            Archive.Kind.ZSTD -> decompressThenMaybeUntar(ctx, file, onLog, "zstd") { Archive.unzstd(it) }
            Archive.Kind.TAR -> {
                val outDir = File(workDir(ctx), file.nameWithoutExtension + "_extracted").apply { mkdirs() }
                file.inputStream().use {
                    val tops = Archive.untar(it, outDir)
                    onLog("tar 展開: ${outDir.name}/ → ${tops.joinToString(", ")}")
                }
            }
            Archive.Kind.DEB -> {
                val outDir = File(workDir(ctx), file.nameWithoutExtension + "_deb").apply { mkdirs() }
                try {
                    onLog("deb 検出 → " + Archive.extractDeb(file, outDir))
                    onLog("展開先: ${outDir.absolutePath}")
                } catch (e: Throwable) {
                    onLog("deb 展開エラー: ${e.message}(xz/zst の実装差異の可能性)")
                }
            }
            Archive.Kind.ZIP ->
                onLog("zip を検出。必要なら zip 展開も追加できます(現状は未対応)。")
            Archive.Kind.UNKNOWN ->
                onLog("不明な形式。実行ファイルなら ELF ヘッダが必要です。システムで開くには外部アプリへ。")
        }
    }

    /** gz/xz/zst を復元 → 中身が tar なら展開、そうでなければ復元ファイルを保存。 */
    private fun decompressThenMaybeUntar(
        ctx: Context, file: File, onLog: (String) -> Unit, label: String,
        decoder: (java.io.InputStream) -> java.io.InputStream,
    ) {
        onLog("$label を検出 → 復元")
        try {
            // まず一時ファイルへ復元(tar 判定のため先頭を見たいので実体化)
            val restored = File(workDir(ctx), file.nameWithoutExtension + ".restored")
            file.inputStream().use { raw -> decoder(raw).use { d -> restored.outputStream().use { d.copyTo(it) } } }
            if (Archive.detect(restored) == Archive.Kind.TAR) {
                val outDir = File(workDir(ctx), file.nameWithoutExtension + "_extracted").apply { mkdirs() }
                restored.inputStream().use {
                    val tops = Archive.untar(it, outDir)
                    onLog("$label+tar 展開: ${outDir.name}/ → ${tops.joinToString(", ")}")
                }
                restored.delete()
            } else {
                onLog("$label 復元完了: ${restored.name}(${restored.length()} bytes)")
            }
        } catch (e: Throwable) {
            onLog("$label 復元エラー: ${e.message}")
        }
    }

    /** EXE などの登録実体を置く安定ディレクトリ。 */
    fun appsDir(ctx: Context): File = File(ctx.filesDir, "apps").apply { mkdirs() }

    /** 登録済みエントリ(アプリ一覧)を起動する。 */
    fun launch(ctx: Context, entry: AppRegistry.Entry, onLog: (String) -> Unit) {
        val f = File(entry.path)
        if (!f.exists()) { onLog("実体が見つかりません: ${entry.path}"); return }
        when (entry.type) {
            AppRegistry.Type.ELF -> runElf(ctx, f, onLog, alreadyInBin = true, extraArgs = entry.args)
            AppRegistry.Type.EXE -> runPe(ctx, f, onLog, extraArgs = entry.args)
        }
    }

    /**
     * Windows PE(.exe/.dll)。Android/Linux では直接は動かない。
     * Box64+Wine が glibc 領域にあれば WINEPREFIX/DISPLAY を設定して起動を試み、無ければ要件を提示。
     * いずれの場合もアプリ一覧に EXE として登録する(環境を入れた後にタップ起動できる)。
     */
    private fun runPe(ctx: Context, file: File, onLog: (String) -> Unit, extraArgs: List<String> = emptyList()) {
        onLog("Windows PE(MZ)を検出: ${file.name}")
        val exe = if (file.parentFile == appsDir(ctx)) file
        else File(appsDir(ctx), file.name).also { file.copyTo(it, overwrite = true) }
        AppRegistry.add(ctx, AppRegistry.Entry(exe.name, AppRegistry.Type.EXE, exe.absolutePath, extraArgs))
        onLog("アプリ一覧に登録(EXE): ${exe.name}")

        val wine = File(GlibcRuntime.binDir(ctx), "wine")
        if (GlibcRuntime.isInstalled(ctx) && wine.exists()) {
            onLog("Wine を検出 → 起動を試行(WINEPREFIX/DISPLAY 設定)")
            val env = mapOf(
                "WINEPREFIX" to File(ctx.filesDir, "wineprefix").absolutePath,
                "WINEDEBUG" to "-all",
                "DISPLAY" to (System.getenv("DISPLAY") ?: ":0"),
                "HOME" to ctx.filesDir.absolutePath,
            )
            val out = GlibcRuntime.runViaLoader(ctx, wine.absolutePath, listOf(exe.absolutePath) + extraArgs, env)
            onLog("--- 出力 ---\n${out.ifBlank { "(出力なし / GUI は X サーバが必要)" }}")
        } else {
            onLog(
                "Windows の .exe は Android(ARM)では単体では動きません。実行には次が必要です(未同梱):\n" +
                    " ・Box64/Box86(x86/x64→ARM 変換)\n" +
                    " ・Wine(Windows 互換層, glibc 上)\n" +
                    " ・X サーバ + GPU(DXVK/Turnip) … ゲーム描画用\n" +
                    "これらを glibc 領域(${GlibcRuntime.binDir(ctx)}/wine など)に配置すると、\n" +
                    "アプリ一覧のこのエントリのタップで wine 起動を試みます。\n" +
                    "実動している既存スタックは Winlator / Mobox 等が参考になります。"
            )
        }
    }

    /** 端末のネイティブ ELF マシン種別(arm64 端末なら EM_AARCH64)。 */
    private fun deviceMachine(): Int {
        val abis = android.os.Build.SUPPORTED_ABIS
        return when {
            abis.any { it == "arm64-v8a" } -> Archive.EM_AARCH64
            abis.any { it.startsWith("armeabi") } -> Archive.EM_ARM
            abis.any { it == "x86_64" } -> Archive.EM_X86_64
            abis.any { it == "x86" } -> Archive.EM_386
            else -> -1
        }
    }

    /** ELF を実行。CPU アーキと glibc 依存を見て経路を選ぶ。実行後アプリ登録する。 */
    private fun runElf(
        ctx: Context, file: File, onLog: (String) -> Unit,
        alreadyInBin: Boolean = false, extraArgs: List<String> = emptyList(),
    ) {
        val exe = if (alreadyInBin) file else File(NativeExecutor.binDir(ctx), file.name).also {
            file.copyTo(it, overwrite = true)
        }
        exe.setExecutable(true, false)
        AppRegistry.add(ctx, AppRegistry.Entry(exe.name, AppRegistry.Type.ELF, exe.absolutePath, extraArgs))

        val mach = Archive.elfMachine(exe)
        val dev = deviceMachine()
        val x86 = mach == Archive.EM_X86_64 || mach == Archive.EM_386

        // 端末アーキと一致しない ELF は「直接実行」しない(ENOEXEC → シェルが誤読して syntax error になる)
        if (mach != dev && x86) {
            val box64 = File(GlibcRuntime.binDir(ctx), if (mach == Archive.EM_X86_64) "box64" else "box86")
            if (box64.exists()) {
                onLog("x86 ELF を検出 → ${box64.name} 経由で実行")
                val out = GlibcRuntime.runViaLoader(ctx, box64.absolutePath, listOf(exe.absolutePath) + extraArgs)
                onLog("--- 出力 ---\n${out.ifBlank { "(出力なし)" }}")
            } else {
                onLog(
                    "これは x86/x64 向けの ELF(${machName(mach)})で、この ARM 端末では直接実行できません。\n" +
                        "実行には Box64/Box86(x86→ARM 変換)が必要です。\n" +
                        "${box64.absolutePath} に配置すると自動で box64 経由実行します。"
                )
            }
            return
        }
        if (mach != dev && mach != -1) {
            onLog("非対応アーキの ELF(${machName(mach)})です。この端末(${machName(dev)})では実行できません。")
            return
        }

        val needsGlibc = Archive.elfNeedsGlibc(exe)
        onLog("ELF 実行(${if (needsGlibc) "glibc ローダ経由" else "Bionic 直接"}): ${exe.name}")
        val out = if (needsGlibc) {
            if (!GlibcRuntime.isInstalled(ctx)) GlibcRuntime.install(ctx, onLog)
            GlibcRuntime.runViaLoader(ctx, exe.absolutePath, extraArgs)
        } else {
            NativeExecutor.run(ctx, exe.absolutePath, extraArgs)
        }
        onLog("アプリ一覧に登録: ${exe.name}")
        onLog("--- 出力 ---\n${out.ifBlank { "(出力なし)" }}")
    }

    private fun machName(m: Int): String = when (m) {
        Archive.EM_AARCH64 -> "arm64"
        Archive.EM_ARM -> "arm"
        Archive.EM_X86_64 -> "x86_64"
        Archive.EM_386 -> "x86"
        -1 -> "非ELF"
        else -> "machine#$m"
    }

    private fun queryName(ctx: Context, uri: Uri): String {
        var name = "picked.bin"
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        } catch (_: Exception) {}
        return name.substringAfterLast('/').ifBlank { "picked.bin" }
    }
}
