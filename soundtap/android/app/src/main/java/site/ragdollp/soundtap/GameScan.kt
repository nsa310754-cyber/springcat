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
            var unreadable = 0
            var noTaps = 0
            val seen = HashSet<String>()
            val sc = UnityScanner(outDir, onFound = { f ->
                val lvl = try {
                    val c = AdofaiChart.parse(f.file.readText())
                    if (c.times.isEmpty()) { noTaps++; null }
                    else Level(f.assetName, c.title, c.artist, c.times.size, f.file, f.source)
                } catch (e: Exception) {
                    unreadable++
                    note("  譜面らしきもの (${f.size}B, ${f.assetName}) を読めず: ${e.message}")
                    null
                }
                // 同じ譜面が複数のファイルに入っていることがあるので、名前と中身が同じものは 1 つにまとめる
                val key = lvl?.let { "${it.assetName}|${f.size}|${f.file.readBytes().contentHashCode()}" }
                if (lvl != null && seen.add(key!!)) {
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
            note("合計: ${found.size} コース / パターン一致 ${sc.patternHits} 回 / 切り出し失敗 ${sc.missed} 回 / 読めない譜面 $unreadable 件 / タップ 0 の譜面 $noTaps 件 / 重複除外後 ${found.size} / ${sc.bytesScanned} bytes")
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
        // DLC (Neo Cosmos) やコラボ (Muse Dash 等) は後からダウンロードされ、アプリのデータ領域や
        // Unity のキャッシュ (UnityCache / cache) に置かれることがあるので、アプリの領域を丸ごと見る
        val dirs = listOf(
            "/data/data/$PKG",
            "/data/user/0/$PKG",
            "/data/user_de/0/$PKG",
            "/data/media/0/Android/obb/$PKG",
            "/data/media/0/Android/data/$PKG",
        ).joinToString(" ") { RootShell.quote(it) }
        // pm path に出ない分割 APK (アセットパック) も拾うため、インストール先フォルダの APK も全部見る
        val script = """
            appdir=${'$'}(pm path $PKG | head -n 1 | sed 's/^package://' | xargs dirname 2>/dev/null)
            for d in ${'$'}appdir $dirs; do n=${'$'}(find "${'$'}d" -type f 2>/dev/null | wc -l); echo "#dir ${'$'}n ${'$'}d"; done
            { pm path $PKG | sed 's/^package://'; [ -n "${'$'}appdir" ] && find "${'$'}appdir" -type f -name '*.apk'; find $dirs -type f 2>/dev/null; } |
            while read -r f; do s=${'$'}(stat -c %s "${'$'}f" 2>/dev/null) && echo "${'$'}s|${'$'}f"; done
        """.trimIndent()
        val (code, out) = RootShell.run(script, 120)
        note("list exit=$code")
        out.lineSequence().filter { it.startsWith("#dir ") }.forEach { note("フォルダ内のファイル数: ${it.removePrefix("#dir ")}") }
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

    private val xLevel = Regex("""(?<![A-Za-z0-9])([A-Za-z0-9]{1,4})-X(?![A-Za-z0-9])""")

    /** 「1-X」「AR-X」のような各ワールドの X (ボス) ステージか。アセット名を優先し、無ければ曲名側も見る */
    fun isXLevel(l: Level): Boolean = xLevel.containsMatchIn(l.assetName) ||
        (l.assetName.isBlank() && xLevel.containsMatchIn(l.title))

    private fun worldKey(l: Level): String =
        xLevel.find(l.assetName)?.groupValues?.get(1) ?: xLevel.find(l.title)?.groupValues?.get(1) ?: l.label

    /** ワールド順 (数字のワールドは 1, 2, …, 12、その後に英字のワールド) */
    val worldOrder = Comparator<Level> { a, b ->
        val ka = worldKey(a); val kb = worldKey(b)
        val na = ka.toIntOrNull(); val nb = kb.toIntOrNull()
        when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> -1
            nb != null -> 1
            else -> ka.compareTo(kb, ignoreCase = true)
        }
    }

    /** 見つけたコースを自動モードのコース一覧へ追加 */
    fun addToCourses(ctx: Context, lvl: Level): File {
        val base = (if (lvl.assetName.isNotBlank()) "${lvl.assetName} ${lvl.title}" else lvl.title)
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(60).ifEmpty { "course" }
        val f = File(RhythmPlayer.coursesDir(ctx), "$base.adofai")
        lvl.file.copyTo(f, overwrite = true)
        return f
    }
}
