package com.example.bionicdesktop

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * glibc ランタイム(フレーム同梱 + 本体は初回DL 方式)。
 *
 * 【方針(調査結果に基づく)】
 * Android は Bionic なので glibc バイナリはそのままでは動かない(ローダ ld-linux-*.so.1 と
 * glibc 共有ライブラリが無い)。Termux も glibc 本体は自作せず「配送 + 起動補助(grun)」だけ。
 * 本アプリは Termux を使わず、同じ原理を自前で行う:
 *
 *   方式①(ローダ明示起動 / 無改変): ld.so --library-path <libs> ./program
 *   方式②(patchelf 書き換え)      : interpreter と rpath を同梱ローダに書き換えてから直接実行
 *
 * ここではフレーム(ディレクトリ構成・ランナー)だけ用意し、glibc 本体(ローダ+libc 等)は
 * 初回に runtimeUrl からダウンロードして展開する。root は不要(単純なバイナリの範囲)。
 * 複雑なバイナリ(/proc・fakeroot 多用)は将来 proot バイナリ同梱で対応する。
 */
object GlibcRuntime {

    /** glibc 一式の展開先。 */
    fun root(ctx: Context): File = File(ctx.filesDir, "glibc").apply { mkdirs() }

    /** 端末 ABI に対応したローダのファイル名。 */
    private fun loaderName(): String =
        if (android.os.Build.SUPPORTED_64_BIT_ABIS.any { it.startsWith("arm") })
            "ld-linux-aarch64.so.1" else "ld-linux-x86-64.so.2"

    fun loaderPath(ctx: Context): File = File(root(ctx), "lib/${loaderName()}")
    fun libDir(ctx: Context): File = File(root(ctx), "lib")

    /** glibc 本体がすでに展開済みか。 */
    fun isInstalled(ctx: Context): Boolean = loaderPath(ctx).exists()

    /**
     * 初回セットアップ: 自前ホストの rootfs/ランタイム tar.gz を落として展開する。
     * ※ URL はユーザが用意する配布先に差し替える。ここでは枠組みだけ。
     *   例) Ubuntu arm64 の最小 rootfs tarball、または ld.so+libc を固めたもの。
     */
    fun download(ctx: Context, runtimeUrl: String, onLog: (String) -> Unit): Boolean {
        return try {
            onLog("ダウンロード開始: $runtimeUrl")
            val dest = File(ctx.cacheDir, "glibc-runtime.tar.gz")
            (URL(runtimeUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                disconnect()
            }
            onLog("展開中…")
            // 展開は同梱の busybox/tar(Bionic ELF)で行う。nativeLibDir の libtar.so を使う想定。
            val tar = File(NativeExecutor.nativeLibDir(ctx), "libtar.so")
            val log = if (tar.exists()) {
                NativeExecutor.run(ctx, tar.absolutePath,
                    listOf("xzf", dest.absolutePath, "-C", root(ctx).absolutePath))
            } else {
                NativeExecutor.sh(ctx, "tar xzf ${dest.absolutePath} -C ${root(ctx).absolutePath}")
            }
            onLog(log)
            onLog(if (isInstalled(ctx)) "✓ glibc 準備完了" else "✗ ローダが見つかりません(URL/中身を確認)")
            isInstalled(ctx)
        } catch (e: Exception) {
            onLog("DL/展開 失敗: ${e.message}")
            false
        }
    }

    /**
     * 方式①: glibc バイナリをローダ経由で実行(無改変)。
     *   ld.so --library-path <libs> <program> [args...]
     */
    fun runViaLoader(ctx: Context, program: String, args: List<String> = emptyList()): String {
        if (!isInstalled(ctx)) return "glibc 未インストール。先に『glibc ランタイムを取得』を実行してください。"
        val loader = loaderPath(ctx).absolutePath
        val libs = listOf(
            libDir(ctx).absolutePath,
            File(root(ctx), "lib/aarch64-linux-gnu").absolutePath,
            File(root(ctx), "usr/lib/aarch64-linux-gnu").absolutePath,
        ).joinToString(":")
        // Termux と違い自前アプリは LD_PRELOAD を注入しないので unset 不要(むしろ有利)。
        return NativeExecutor.run(
            ctx, loader,
            listOf("--library-path", libs, program) + args,
            env = mapOf("LD_LIBRARY_PATH" to libs),
        )
    }
}
