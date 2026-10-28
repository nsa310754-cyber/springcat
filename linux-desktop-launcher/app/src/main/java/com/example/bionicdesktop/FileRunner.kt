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

    /** Windows PE(.exe/.dll)。Android/Linux では直接は動かない。Wine があれば起動、無ければ要件を提示。 */
    private fun runPe(ctx: Context, file: File, onLog: (String) -> Unit) {
        onLog("Windows PE(MZ)を検出: ${file.name}")
        val wine = File(GlibcRuntime.binDir(ctx), "wine")
        if (GlibcRuntime.isInstalled(ctx) && wine.exists()) {
            onLog("Wine を検出 → glibc ローダ経由で起動を試行")
            val out = GlibcRuntime.runViaLoader(ctx, wine.absolutePath, listOf(file.absolutePath))
            onLog("--- 出力 ---\n${out.ifBlank { "(出力なし)" }}")
        } else {
            onLog(
                "Windows PE は Android(Bionic/ARM)では直接実行できません。\n" +
                    "実行には次が必要です(本アプリには未同梱):\n" +
                    " ・Box64/Box86(x86→ARM 変換)\n" +
                    " ・Wine(Windows 互換層, glibc 上)\n" +
                    "これらを glibc 領域(${GlibcRuntime.binDir(ctx)}/wine)に配置すると、\n" +
                    "本ボタンから自動で wine 起動を試みます。"
            )
        }
    }

    /** ELF を実行。glibc 依存を判定して実行経路を選ぶ。 */
    private fun runElf(ctx: Context, file: File, onLog: (String) -> Unit) {
        val needsGlibc = Archive.elfNeedsGlibc(file)
        // 実行用に bin へ配置(filesDir は targetSdk28 なので実行可)
        val exe = File(NativeExecutor.binDir(ctx), file.name)
        file.copyTo(exe, overwrite = true)
        exe.setExecutable(true, false)
        onLog("ELF 実行(${if (needsGlibc) "glibc ローダ経由" else "Bionic 直接"}): ${exe.name}")
        val out = if (needsGlibc) {
            if (!GlibcRuntime.isInstalled(ctx)) GlibcRuntime.install(ctx, onLog)
            GlibcRuntime.runViaLoader(ctx, exe.absolutePath)
        } else {
            NativeExecutor.run(ctx, exe.absolutePath)
        }
        onLog("--- 出力 ---\n${out.ifBlank { "(出力なし)" }}")
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
