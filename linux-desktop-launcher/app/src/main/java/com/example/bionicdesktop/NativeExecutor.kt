package com.example.bionicdesktop

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Bionic ネイティブ ELF 実行ファイルを実行する。
 *
 * 【なぜ動くか】
 * Android 本体の libc は Bionic なので、NDK で Bionic 向けにビルドした ARM/x86 の ELF は
 * そのまま実行できる(Windows の .exe ではなく、Android ネイティブ実行ファイル)。
 *
 * 【どこに置けば実行できるか】
 * ・targetSdk 28 のとき: アプリの filesDir 内のファイルを chmod +x して exec() 可能。
 *   (targetSdk 29+ だと W^X / SELinux で filesDir からの実行は禁止される。だから 28。)
 * ・targetSdk に関わらず: APK 同梱 native lib 置き場 (applicationInfo.nativeLibraryDir) の
 *   ファイルは常に実行可能。恒久的に同梱したいバイナリは lib○○.so 名で jniLibs に入れる。
 */
object NativeExecutor {

    /** 実行用バイナリの置き場所(filesDir/bin)。targetSdk 28 なのでここから実行できる。 */
    fun binDir(ctx: Context): File = File(ctx.filesDir, "bin").apply { mkdirs() }

    /** APK 同梱 native バイナリの置き場(常に実行可)。 */
    fun nativeLibDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    /** 実行権限を付与する。 */
    fun makeExecutable(file: File): Boolean = file.setExecutable(true, false)

    /**
     * ELF をフォアグラウンドで実行し、stdout+stderr をまとめて返す。
     * @param path   実行ファイルの絶対パス(filesDir/bin 以下 or nativeLibraryDir 以下)
     * @param args   引数
     * @param env    追加の環境変数
     * @param workDir 作業ディレクトリ(既定: filesDir)
     */
    fun run(
        ctx: Context,
        path: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        workDir: File = ctx.filesDir,
    ): String {
        val f = File(path)
        if (f.exists()) makeExecutable(f)
        return try {
            val pb = ProcessBuilder(listOf(path) + args)
            pb.directory(workDir)
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("HOME", ctx.filesDir.absolutePath)
                put("TMPDIR", ctx.cacheDir.absolutePath)
                put("PATH", "${binDir(ctx).absolutePath}:${nativeLibDir(ctx).absolutePath}:/system/bin")
                putAll(env)
            }
            val p = pb.start()
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            reader.forEachLine { sb.appendLine(it) }
            p.waitFor()
            sb.toString()
        } catch (e: Exception) {
            "実行失敗: ${e.message}\n" +
                "(targetSdk が 29 以上だと filesDir からの実行は W^X で禁止されます。28 を確認してください)"
        }
    }

    /** /system/bin/sh 経由で任意のシェル行を実行(コマンドプロンプト用)。 */
    fun sh(ctx: Context, commandLine: String): String =
        run(ctx, "/system/bin/sh", listOf("-c", commandLine))

    /**
     * 待たずにプロセスを起動して Process を返す(X サーバ等の常駐用)。
     * 呼び出し側が参照を保持し、終了時に destroy すること。
     */
    fun runDetached(
        ctx: Context,
        path: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        workDir: File = ctx.filesDir,
    ): Process {
        File(path).takeIf { it.exists() }?.let { makeExecutable(it) }
        val pb = ProcessBuilder(listOf(path) + args)
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", ctx.filesDir.absolutePath)
            put("TMPDIR", ctx.cacheDir.absolutePath)
            put("PATH", "${binDir(ctx).absolutePath}:${nativeLibDir(ctx).absolutePath}:/system/bin")
            putAll(env)
        }
        return pb.start()
    }
}
