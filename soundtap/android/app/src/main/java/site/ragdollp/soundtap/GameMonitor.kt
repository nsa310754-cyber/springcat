package site.ragdollp.soundtap

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.URLDecoder

/**
 * root で ADOFAI を見張り、今遊んでいるステージを見つけたら、そのコースを選んで
 * 「スタートは自分で」+「1 枚目を自分で押して間を測る」をオンにし、バーを出す。
 *
 * 手がかりにしているもの (どちらも読み取りのみ):
 * - ADOFAI のログ (logcat, ゲームのプロセスに絞る)
 * - ADOFAI の設定ファイル (shared_prefs の PlayerPrefs) の変化
 * どちらかに、スキャン済みコースのステージ名 (例 "AR-X") か曲名が出たら、そのステージとみなす。
 * 何が出ているかは「監視ログ」で確認できる (合わないときの調整用)。
 */
object GameMonitor {
    private const val PKG = GameScan.PKG

    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val detected = MutableStateFlow<String?>(null)
    /** 監視で見えたもの (最新が後ろ) */
    val lines = MutableStateFlow<List<String>>(emptyList())
    /** 設定ファイルから見つけた音量らしき値 (例 "musicVolume=0") */
    val volumes = MutableStateFlow("")

    @Volatile private var stopFlag = false
    private var thread: Thread? = null
    private var logStream: java.io.InputStream? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastDetectAt = 0L

    private val idPattern = Regex("""(?<![A-Za-z0-9])([A-Za-z0-9]{1,4}-[A-Za-z0-9]{1,3})(?![A-Za-z0-9])""")

    fun start(ctx: Context) {
        if (running.value) return
        val app = ctx.applicationContext
        GameScan.loadIndex(app)
        stopFlag = false
        running.value = true
        status.value = "開始中…"
        thread = Thread({ run(app) }, "adofai-monitor").also { it.start() }
    }

    fun stop() {
        stopFlag = true
        try { logStream?.close() } catch (_: Exception) {}
        thread?.interrupt()
        thread = null
        running.value = false
        status.value = "停止中"
    }

    private fun note(s: String) {
        val l = (lines.value + s).takeLast(80)
        lines.value = l
    }

    private fun run(ctx: Context) {
        try {
            if (!RootShell.available()) {
                status.value = "root 権限を得られませんでした"
                return
            }
            var pid = ""
            var logThread: Thread? = null
            var prefs = emptyMap<String, String>()
            var firstPrefs = true
            while (!stopFlag) {
                // ゲームのプロセスを探す (起動し直したらログの読み直し)
                val newPid = RootShell.run("pidof $PKG", 10).second.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                if (newPid != pid) {
                    pid = newPid
                    try { logStream?.close() } catch (_: Exception) {}
                    logThread?.interrupt()
                    if (pid.isNotEmpty() && pid.all { it.isDigit() }) {
                        status.value = "ADOFAI を監視中 (pid $pid)"
                        logThread = startLog(ctx, pid)
                    } else {
                        status.value = "ADOFAI が起動していません (起動を待っています)"
                    }
                }

                // 設定ファイルの変化
                val now = readPrefs()
                if (now.isNotEmpty()) {
                    if (!firstPrefs) {
                        for ((k, v) in now) {
                            if (prefs[k] != v) {
                                val line = "pref $k = ${v.take(120)}"
                                note(line)
                                match(ctx, "$k $v")
                            }
                        }
                    }
                    firstPrefs = false
                    prefs = now
                    volumes.value = now.filterKeys { k ->
                        val l = k.lowercase()
                        l.contains("vol") || l.contains("music") || l.contains("hitsound") || l.contains("sfx")
                    }.entries.take(8).joinToString("  ") { "${it.key}=${it.value.take(12)}" }
                }
                try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
            }
            logThread?.interrupt()
        } catch (e: Exception) {
            status.value = "監視エラー: ${e.message}"
        } finally {
            try { logStream?.close() } catch (_: Exception) {}
            running.value = false
        }
    }

    /** ゲームのプロセスのログを流し読みする */
    private fun startLog(ctx: Context, pid: String): Thread = Thread({
        try {
            val s = RootShell.stream("logcat -v brief -T 1 --pid=$pid")
            logStream = s
            s.bufferedReader().useLines { seq ->
                for (line in seq) {
                    if (stopFlag) break
                    // 手がかりになりそうな行だけ監視ログに残す
                    val l = line.lowercase()
                    if (l.contains("level") || l.contains("load") || l.contains("scene") || l.contains("song") ||
                        idPattern.containsMatchIn(line)
                    ) note("log ${line.take(160)}")
                    match(ctx, line)
                }
            }
        } catch (_: Exception) {}
    }, "adofai-logcat").also { it.start() }

    /** shared_prefs の XML を読んで key → value にする (PlayerPrefs のキーは URL エンコードされている) */
    private fun readPrefs(): Map<String, String> {
        val (code, out) = RootShell.run("cat /data/data/$PKG/shared_prefs/*.xml 2>/dev/null", 10)
        if (code != 0 && out.isBlank()) return emptyMap()
        val m = LinkedHashMap<String, String>()
        val re = Regex("""<(string|int|float|long|boolean) name="([^"]*)"(?: value="([^"]*)")?\s*/?>(?:([^<]*)</string>)?""")
        for (r in re.findAll(out)) {
            val key = decode(r.groupValues[2])
            val v = r.groupValues[3].ifEmpty { decode(r.groupValues[4]) }
            m[key] = v
        }
        return m
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">"), "UTF-8")
    } catch (_: Exception) { s }

    /** 行の中にスキャン済みコースのステージ名か曲名があれば、そのコースを選ぶ */
    private fun match(ctx: Context, text: String) {
        val levels = GameScan.levels.value
        if (levels.isEmpty()) return
        // 1) ステージ名 (例 AR-X, 1-X, 12-3)
        val ids = idPattern.findAll(text).map { it.groupValues[1].uppercase() }.toSet()
        var hit = levels.firstOrNull { it.assetName.isNotBlank() && it.assetName.uppercase() in ids }
        // 2) 曲名 (短すぎる名前は誤検出しやすいので 5 文字以上)
        if (hit == null) {
            val lower = text.lowercase()
            hit = levels.filter { it.title.length >= 5 }.firstOrNull { lower.contains(it.title.lowercase()) }
        }
        hit ?: return
        val nowMs = System.currentTimeMillis()
        if (detected.value == hit.label && nowMs - lastDetectAt < 5000) return
        lastDetectAt = nowMs
        main.post { apply(ctx, hit) }
    }

    /** 見つけたステージをコースに追加して選び、開始方法を「自分でスタート + 間を測る」にして待機 */
    private fun apply(ctx: Context, lvl: GameScan.Level) {
        val f: File = GameScan.addToCourses(ctx, lvl)
        Config.selectedCourse = f.name
        Config.rhythmStartMode = Config.START_TOUCH
        Config.rhythmMeasure = true
        Config.save(ctx)
        RhythmPlayer.courseKey = ""
        RhythmPlayer.loadSelected(ctx)
        detected.value = lvl.label
        note("→ 検出: ${lvl.label}")
        Engine.configVersion.value = Engine.configVersion.value + 1
        // バーを出す。待機 (▶) は自分で押す: マップ上でステージ名が出ただけの段階で待機に入ると、
        // メニュー操作のタップを「スタート」と取り違えて、間の測定を壊してしまうため
        if (TapService.instance != null) {
            RhythmPlayer.barVisible = true
            TapService.instance?.refreshOverlays()
        }
    }
}
