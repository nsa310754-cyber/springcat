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
            Archive.Kind.GZIP -> {
                onLog("gzip を検出 → 展開")
                try {
                    file.inputStream().use { raw ->
                        val gz = Archive.gunzip(raw)
                        // tar.gz かどうか: 展開ストリームを untar に通し、失敗すれば生ファイル保存に切替
                        val outDir = File(workDir(ctx), file.nameWithoutExtension + "_extracted").apply { mkdirs() }
                        val tops = Archive.untar(gz, outDir)
                        onLog("展開完了: ${outDir.name}/  → ${tops.joinToString(", ")}")
                    }
                } catch (e: Exception) {
                    onLog("tar.gz ではないかエラー: ${e.message}")
                }
            }
            Archive.Kind.TAR -> {
                val outDir = File(workDir(ctx), file.nameWithoutExtension + "_extracted").apply { mkdirs() }
                file.inputStream().use {
                    val tops = Archive.untar(it, outDir)
                    onLog("tar 展開: ${outDir.name}/ → ${tops.joinToString(", ")}")
                }
            }
            Archive.Kind.DEB -> {
                val outDir = File(workDir(ctx), file.nameWithoutExtension + "_deb").apply { mkdirs() }
                onLog("deb 検出 → " + Archive.extractDeb(file, outDir))
                onLog("展開先: ${outDir.absolutePath}")
            }
            Archive.Kind.XZ, Archive.Kind.ZSTD ->
                onLog("xz/zst 圧縮は非対応です。gz 形式を使うか、PC で tar.gz に変換してください。")
            Archive.Kind.ZIP ->
                onLog("zip を検出。必要なら zip 展開も追加できます(現状は未対応)。")
            Archive.Kind.UNKNOWN ->
                onLog("不明な形式。実行ファイルなら ELF ヘッダが必要です。システムで開くには外部アプリへ。")
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
