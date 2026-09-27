package site.ragdollp.soundtap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Accent = Color(0xFF3DDC97)
private val Warn = Color(0xFFFFB547)
private val Danger = Color(0xFFE5484D)
private val Bg = Color(0xFF101418)
private val Surface = Color(0xFF192028)
private val Muted = Color(0xFF93A0AD)

class MainActivity : ComponentActivity() {

    private var resumeTick by mutableIntStateOf(0)
    private var configTick by mutableIntStateOf(0)
    private var pendingStart = false
    private val main = Handler(Looper.getMainLooper())

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        resumeTick++
        if (ok && pendingStart) requestStart()
        else if (!ok) toast("マイク権限がないと音を検知できません")
        pendingStart = false
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumeTick++
    }
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null) {
            DetectorService.start(this, r.resultCode, r.data)
        } else {
            toast("端末内の音を使うには「開始」を許可してください")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.load(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Color(0xFF002114),
                    background = Bg, surface = Surface, onSurface = Color.White,
                    surfaceVariant = Surface, secondaryContainer = Color(0xFF24403A),
                )
            ) {
                Screen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Config.load(this)
        resumeTick++
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun update(block: () -> Unit) {
        block()
        Config.save(this)
        configTick++
    }

    private fun requestStart() {
        if (!hasMic()) {
            pendingStart = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (TapService.instance == null) toast("ユーザー補助 (タップ操作) がオフのため、検知だけ行います")
        else if (Config.tapX < 0) toast("タップ位置が未設定です。「位置を設定」から指定してください")
        Engine.triggerCount.value = 0
        Engine.lastLatencyMs.value = -1f
        if (Config.source == Config.SOURCE_INTERNAL && Build.VERSION.SDK_INT >= 29) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            DetectorService.start(this)
        }
    }

    private fun stop() {
        DetectorService.instance?.stopDetection()
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun setPosition() {
        val svc = TapService.instance ?: return toast("先にユーザー補助をオンにしてください")
        svc.startEditing()
        toast("タップしたい画面を開いて、照準をドラッグ → ✓ 決定")
        moveTaskToBack(true)
    }

    private fun testTap() {
        if (TapService.instance == null) return toast("先にユーザー補助をオンにしてください")
        if (Config.tapX < 0) return toast("先にタップ位置を設定してください")
        toast("3秒後にタップします。対象の画面へ切り替えてください")
        main.postDelayed({ TapService.instance?.tapNow() }, 3000)
    }

    // ================================================================ UI

    @Composable
    private fun Screen() {
        @Suppress("UNUSED_VARIABLE") val r = resumeTick
        @Suppress("UNUSED_VARIABLE") val c = configTick
        val running by Engine.running.collectAsState()
        val armed by Engine.armed.collectAsState()
        val tapOk by Engine.tapServiceConnected.collectAsState()
        val count by Engine.triggerCount.collectAsState()
        val latency by Engine.lastLatencyMs.collectAsState()
        val trigDb by Engine.lastTriggerDb.collectAsState()
        val msg by Engine.message.collectAsState()
        val extVer by Engine.configVersion.collectAsState()
        LaunchedEffect(extVer) { Config.load(this@MainActivity); configTick++ }

        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Text("SoundTap", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
                Text("音が鳴った瞬間に、指定位置を自動タップ", color = Muted, fontSize = 14.sp)
            }

            // ---- セットアップ
            Section("セットアップ") {
                StatusRow(
                    ok = tapOk,
                    title = "ユーザー補助 (タップ操作)",
                    detail = if (tapOk) "有効" else "設定 → ユーザー補助 → SoundTap をオン",
                    action = if (tapOk) null else "設定を開く" to ::openAccessibility,
                )
                if (!tapOk) {
                    Text(
                        "「制限付き設定」と表示されて押せない場合: アプリ情報 → 右上︙ → 「制限付き設定を許可」してから再度オンにしてください。",
                        color = Muted, fontSize = 12.sp, lineHeight = 17.sp
                    )
                    TextButton(onClick = ::openAppInfo) { Text("アプリ情報を開く") }
                }
                StatusRow(
                    ok = hasMic(),
                    title = "マイク権限",
                    detail = if (hasMic()) "許可済み" else "開始時に確認します",
                    action = if (hasMic()) null else "許可" to { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                )
                StatusRow(
                    ok = Config.tapX >= 0,
                    title = "タップ位置",
                    detail = if (Config.tapX >= 0) "X ${Config.tapX.roundToInt()}  Y ${Config.tapY.roundToInt()}" else "未設定",
                    action = "位置を設定" to ::setPosition,
                )
            }

            // ---- 実行
            Section("実行") {
                Button(
                    onClick = { if (running) stop() else requestStart() },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) Danger else Accent,
                        contentColor = if (running) Color.White else Color(0xFF002114),
                    )
                ) {
                    Text(if (running) "■ 停止" else "▶ 監視を開始", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (armed) "反応する (待機中)" else "一時停止中",
                            color = if (armed) Accent else Muted, modifier = Modifier.weight(1f)
                        )
                        Switch(checked = armed, onCheckedChange = { Engine.setArmed(it) })
                    }
                }
                LevelMeter(running)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("反応回数", "$count", Modifier.weight(1f))
                    Stat("音→タップ", if (latency >= 0) "%.1f ms".format(latency) else "—", Modifier.weight(1f))
                    Stat("反応時の音量", if (count > 0) "%.0f dB".format(trigDb) else "—", Modifier.weight(1f))
                }
                if (msg != null) Text(msg!!, color = Warn, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = ::testTap) { Text("テストタップ (3秒後)") }
                }
                Text(
                    "画面に出る丸いボタン: タップで一時停止/再開・長押しで停止・ドラッグで移動",
                    color = Muted, fontSize = 12.sp, lineHeight = 17.sp
                )
            }

            // ---- 検出
            Section("音の検出") {
                Label("音源")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = Config.source == Config.SOURCE_MIC,
                        onClick = { update { Config.source = Config.SOURCE_MIC } },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        enabled = !running,
                    ) { Text("マイク") }
                    SegmentedButton(
                        selected = Config.source == Config.SOURCE_INTERNAL,
                        onClick = { update { Config.source = Config.SOURCE_INTERNAL } },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        enabled = !running && Build.VERSION.SDK_INT >= 29,
                    ) { Text("端末内の音") }
                }
                Hint(
                    if (Config.source == Config.SOURCE_INTERNAL)
                        "ゲーム等の再生音を直接拾います (Android 10+)。周りの雑音に強い反面、アプリによっては取得を禁止しています。"
                    else "周りの音もすべて拾います。静かな場所で使うか、ノイズ追従をオンに。"
                )

                ValueSlider(
                    "しきい値 (この音量を超えたら反応)", Config.thresholdDb, -80f..0f, 1f,
                    { "%.0f dB".format(it) }
                ) { v -> update { Config.thresholdDb = v } }

                ToggleRow("ノイズ追従", "周りの音量より一定以上大きい「急な音」だけに反応", Config.adaptive) {
                    update { Config.adaptive = it }
                }
                if (Config.adaptive) {
                    ValueSlider("立ち上がり幅", Config.riseDb, 3f..40f, 1f, { "+%.0f dB".format(it) }) { v ->
                        update { Config.riseDb = v }
                    }
                }
                ValueSlider(
                    "低音カット (風・振動・BGMの低音を無視)", Config.highPassHz, 0f..4000f, 50f,
                    { if (it <= 0f) "オフ" else "%.0f Hz".format(it) }
                ) { v -> update { Config.highPassHz = v } }

                Label("検出ブロック (小さいほど高速・負荷増)")
                val blocks = listOf(32, 64, 128, 256)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    blocks.forEachIndexed { i, b ->
                        SegmentedButton(
                            selected = Config.blockFrames == b,
                            onClick = { update { Config.blockFrames = b } },
                            shape = SegmentedButtonDefaults.itemShape(i, blocks.size),
                            enabled = !running,
                        ) { Text("$b") }
                    }
                }
                Hint("48kHz で 64 フレーム ≒ 1.3ms ごとに判定。変更は次回開始時に反映。")
            }

            // ---- タップ
            Section("タップ") {
                ValueSlider(
                    "遅延 (音が鳴った時刻から)", Config.delayMs.toFloat(), 0f..3000f, 1f,
                    { if (it < 0.5f) "即時" else "%.0f ms".format(it) }
                ) { v -> update { Config.delayMs = v.roundToInt() } }
                Hint("遅延は入力遅延を差し引いて音の発生時刻から正確に計ります。")
                ValueSlider("押している時間", Config.pressMs.toFloat(), 1f..300f, 1f, { "%.0f ms".format(it) }) { v ->
                    update { Config.pressMs = v.roundToInt() }
                }
                ValueSlider("タップ回数", Config.tapCount.toFloat(), 1f..20f, 1f, { "%.0f 回".format(it) }) { v ->
                    update { Config.tapCount = v.roundToInt() }
                }
                if (Config.tapCount > 1) {
                    ValueSlider("連打の間隔", Config.tapIntervalMs.toFloat(), 10f..1000f, 1f, { "%.0f ms".format(it) }) { v ->
                        update { Config.tapIntervalMs = v.roundToInt() }
                    }
                }
                ValueSlider(
                    "クールダウン (反応後に無視する時間)", Config.cooldownMs.toFloat(), 0f..5000f, 10f,
                    { "%.0f ms".format(it) }
                ) { v -> update { Config.cooldownMs = v.roundToInt() } }
                ToggleRow("1回反応したら一時停止", "釣りゲームなど 1 回だけ押したいとき", Config.oneShot) {
                    update { Config.oneShot = it }
                }
                ToggleRow("実行中に照準を表示", "タップは照準を素通りします", Config.showMarker) {
                    update { Config.showMarker = it }
                    TapService.instance?.refreshOverlays()
                }
                ToggleRow("フローティングボタンを表示", "画面上で一時停止/再開・音量確認", Config.showBubble) {
                    update { Config.showBubble = it }
                    TapService.instance?.refreshOverlays()
                }
            }

            Section("うまく反応しないとき") {
                Hint("・メーターを見ながら、反応させたい音で黄色になり、普段は緑のままになる位置にしきい値を合わせる")
                Hint("・話し声や BGM で誤反応するなら「ノイズ追従」「低音カット」をオン、または「端末内の音」を使う")
                Hint("・「音→タップ」は端末の入力遅延を含む実測値です。遅延の設定で狙ったタイミングに合わせられます")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                content()
            }
        }
    }

    @Composable
    private fun Label(s: String) = Text(s, color = Color.White, fontSize = 14.sp)

    @Composable
    private fun Hint(s: String) = Text(s, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)

    @Composable
    private fun StatusRow(ok: Boolean, title: String, detail: String, action: Pair<String, () -> Unit>?) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier.size(10.dp).clip(CircleShape).background(if (ok) Accent else Warn)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp)
                Text(detail, color = Muted, fontSize = 12.sp)
            }
            if (action != null) OutlinedButton(onClick = action.second) { Text(action.first) }
        }
    }

    @Composable
    private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp)
                Text(detail, color = Muted, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
        Column(
            modifier.clip(RoundedCornerShape(12.dp)).background(Bg).padding(10.dp)
        ) {
            Text(label, color = Muted, fontSize = 11.sp)
            Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }

    /** スライダー + 微調整ボタン (−/+) */
    @Composable
    private fun ValueSlider(
        label: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        step: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit,
    ) {
        fun snap(v: Float) = ((v / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(format(value), color = Accent, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onChange(snap(value - step)) }) { Text("−", fontSize = 18.sp) }
                Slider(
                    value = value.coerceIn(range.start, range.endInclusive),
                    onValueChange = { onChange(snap(it)) },
                    valueRange = range,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onChange(snap(value + step)) }) { Text("+", fontSize = 18.sp) }
            }
        }
    }

    /** 入力レベル (-80〜0 dB)、ピークホールド、しきい値ライン */
    @Composable
    private fun LevelMeter(running: Boolean) {
        var level by remember { mutableFloatStateOf(-120f) }
        var peak by remember { mutableFloatStateOf(-120f) }
        var thr by remember { mutableFloatStateOf(Config.thresholdDb) }
        LaunchedEffect(running) {
            while (true) {
                level = Engine.levelDb
                peak = Engine.peakHoldDb
                thr = if (running) Engine.effectiveThresholdDb else Config.thresholdDb
                delay(33)
            }
        }
        fun n(db: Float) = ((db + 80f) / 80f).coerceIn(0f, 1f)
        Column {
            Row {
                Text("入力レベル", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    if (running) "%.0f dB  (ピーク %.0f)".format(level.coerceAtLeast(-99f), peak.coerceAtLeast(-99f)) else "停止中",
                    color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val w = size.width; val h = size.height
                val cr = CornerRadius(6.dp.toPx())
                drawRoundRect(Color(0xFF0B0F12), size = size, cornerRadius = cr)
                val over = level >= thr
                drawRoundRect(
                    if (over) Warn else Accent,
                    size = Size(w * n(level), h), cornerRadius = cr
                )
                val px = w * n(peak)
                drawRect(Color.White.copy(alpha = 0.7f), topLeft = Offset(px - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), h))
                val tx = w * n(thr)
                drawRect(Danger, topLeft = Offset(tx - 1.5.dp.toPx(), -3.dp.toPx()), size = Size(3.dp.toPx(), h + 6.dp.toPx()))
            }
        }
    }
}
