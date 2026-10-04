package site.ragdollp.soundtap

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * root 権限で端末内の ADOFAI のデータを直接読み、公式コースの譜面を取り出す。
 * 取り出した譜面は端末内 (このアプリの領域) にだけ保存し、外部には送らない。
 */
object GameScan {
    const val PKG = "com.fizzd.connectedworlds"

    class Level(
        val assetName: String,
        val title: String,
        val artist: String,
        val taps: Int,
        val file: File,
        val source: String,
    ) {
        val label get() = listOf(assetName, title).filter { it.isNotBlank() }.distinct().joinToString(" — ")
    }

    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val levels = MutableStateFlow<List<Level>>(emptyList())
    val report = MutableStateFlow("")

    @Volatile private var scanner: UnityScanner? = null

    fun cancel() { scanner?.cancelled = true }

    fun start(ctx: Context) {
        if (running.value) return
        running.value = true
        levels.value = emptyList()
        report.value = ""
        Thread({ run(ctx.applicationContext) }, "adofai-scan").start()
    }

    private fun run(ctx: Context) {
        val log = StringBuilder()
        fun note(s: String) { synchronized(log) { log.append(s).append('\n') } }
        val outDir = File(ctx.cacheDir, "scan").apply { deleteRecursively(); mkdirs() }
        try {
            status.value = "root 権限を確認中… (許可ダイアログが出たら「許可」)"
            if (!RootShell.available()) {
                status.value = "root 権限を得られませんでした。Magisk 等で SoundTap を許可してください"
                note("su: 失敗")
                return
            }
            note("su: OK")

            status.value = "ADOFAI のファイルを探しています…"
            val files = listGameFiles(::note)
            if (files.isEmpty()) {
                status.value = "ADOFAI ($PKG) のファイルが見つかりません。インストールされていますか？"
                return
            }
            files.forEach { (size, path) -> note("file %,d %s".format(size, path)) }

            val found = ArrayList<Level>()
            val sc = UnityScanner(outDir, onFound = { f ->
                val lvl = try {
                    val c = AdofaiChart.parse(f.file.readText())
                    if (c.times.isEmpty()) null
                    else Level(f.assetName, c.title, c.artist, c.times.size, f.file, f.source)
                } catch (e: Exception) {
                    note("  譜面らしきもの (${f.size}B, ${f.assetName}) を読めず: ${e.message}")
                    null
                }
                if (lvl != null) {
                    found.add(lvl)
                    levels.value = found.toList()
                }
            }, log = ::note)
            scanner = sc

            val total = files.sumOf { it.first }
            var done = 0L
            for ((size, path) in files) {
                if (sc.cancelled) break
                val short = path.substringAfterLast('/')
                status.value = "スキャン中 ${pct(done, total)}%  $short  (見つかったコース ${found.size})"
                val before = sc.bytesScanned
                val t0 = System.currentTimeMillis()
                sc.scanFile(path) { RootShell.open(path) }
                note("scanned %s: %,d bytes, %d ms".format(short, sc.bytesScanned - before, System.currentTimeMillis() - t0))
                done += size
            }
            note("合計: ${found.size} コース / パターン一致 ${sc.patternHits} 回 / ${sc.bytesScanned} bytes")
            status.value = when {
                sc.cancelled -> "中止しました (見つかったコース ${found.size})"
                found.isEmpty() -> "譜面が見つかりませんでした。下の診断レポートをコピーして送ってください"
                else -> "完了: ${found.size} コースを見つけました"
            }
        } catch (e: Throwable) {
            note("例外: ${e.javaClass.name}: ${e.message}")
            status.value = "エラー: ${e.message}"
        } finally {
            scanner = null
            report.value = buildReport(log.toString())
            running.value = false
        }
    }

    private fun pct(a: Long, b: Long) = if (b <= 0) 0 else (a * 100 / b).toInt()

    /** (サイズ, パス) の一覧。大きい順 (データ本体を先に) */
    private fun listGameFiles(note: (String) -> Unit): List<Pair<Long, String>> {
        val dirs = listOf(
            "/data/data/$PKG/files",
            "/data/user/0/$PKG/files",
            "/data/media/0/Android/obb/$PKG",
            "/data/media/0/Android/data/$PKG/files",
        ).joinToString(" ") { RootShell.quote(it) }
        val script = """
            { pm path $PKG | sed 's/^package://'; find $dirs -type f 2>/dev/null; } |
            while read -r f; do s=${'$'}(stat -c %s "${'$'}f" 2>/dev/null) && echo "${'$'}s|${'$'}f"; done
        """.trimIndent()
        val (code, out) = RootShell.run(script, 120)
        note("list exit=$code")
        val seen = HashSet<String>()
        return out.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('|')
                if (i <= 0) return@mapNotNull null
                val size = line.substring(0, i).trim().toLongOrNull() ?: return@mapNotNull null
                val path = line.substring(i + 1).trim()
                // /data/data と /data/user/0 は同じ場所なので重複を除く
                val key = path.replace("/data/user/0/", "/data/data/")
                if (!seen.add(key)) return@mapNotNull null
                if (size < 1024) return@mapNotNull null
                if (path.endsWith(".so") || path.endsWith(".dex") || path.endsWith(".odex") ||
                    path.endsWith(".vdex") || path.endsWith(".art") || path.endsWith(".prof")
                ) return@mapNotNull null
                size to path
            }
            .sortedByDescending { it.first }
            .toList()
    }

    private fun buildReport(log: String): String =
        "SoundTap 診断レポート\n" +
            "Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.MODEL}\n" +
            log.lines().take(400).joinToString("\n")

    /** 見つけたコースを自動モードのコース一覧へ追加 */
    fun addToCourses(ctx: Context, lvl: Level): File {
        val base = (if (lvl.assetName.isNotBlank()) "${lvl.assetName} ${lvl.title}" else lvl.title)
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(60).ifEmpty { "course" }
        val f = File(RhythmPlayer.coursesDir(ctx), "$base.adofai")
        lvl.file.copyTo(f, overwrite = true)
        return f
    }
}
