package site.ragdollp.soundtap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * タップ送信 (dispatchGesture) と、画面上のオーバーレイ
 * (ターゲット照準・位置合わせパネル・フローティング操作ボタン) を担当する。
 * オーバーレイは TYPE_ACCESSIBILITY_OVERLAY なので「他のアプリの上に表示」権限は不要。
 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile var instance: TapService? = null
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density get() = resources.displayMetrics.density

    private var marker: MarkerView? = null
    private var markerLp: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelCoords: TextView? = null
    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var editing = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        Config.load(this)
        instance = this
        Engine.tapServiceConnected.value = true
        refreshOverlays()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        instance = null
        Engine.tapServiceConnected.value = false
        editing = false
        removeMarker(); removePanel(); removeBubble(); removeRhythmBar()
        RhythmPlayer.stop()
    }

    // ---------------------------------------------------------------- タップ

    /**
     * 設定位置をタップする。音声スレッドから直接呼ばれる (dispatchGesture はスレッドセーフ)。
     * 連打は 1 つの GestureDescription に時刻をずらしたストロークとして詰め、システム側で正確に刻ませる。
     */
    fun tapNow(): Boolean {
        val x = Config.tapX
        val y = Config.tapY
        if (x < 0 || y < 0) {
            Engine.message.value = "タップ位置が未設定です"
            return false
        }
        val count = Config.tapCount.coerceIn(1, 20)
        val press = Config.pressMs.coerceIn(1, 1000).toLong()
        val interval = max(Config.tapIntervalMs.toLong(), press + 1)
        val path = Path().apply { moveTo(x, y) }
        val builder = GestureDescription.Builder()
        for (i in 0 until count) {
            builder.addStroke(GestureDescription.StrokeDescription(path, i * interval, press))
        }
        val ok = try { dispatchGesture(builder.build(), null, null) } catch (e: Exception) { false }
        main.post { marker?.flash(); bubble?.flash() }
        return ok
    }

    /** 1 回押す (ADOFAI モード用)。押下時間 ms */
    fun press(x: Float, y: Float, durMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durMs.coerceIn(1, 60_000)))
            .build()
        return try { dispatchGesture(g, null, null) } catch (e: Exception) { false }
    }

    /** ADOFAI モードで叩く位置。設定があればそこ、なければ画面中央やや下 (左上の一時停止ボタンを避ける) */
    fun rhythmPoint(): Pair<Float, Float> {
        if (Config.tapX >= 0 && Config.tapY >= 0) return Config.tapX to Config.tapY
        val dm = resources.displayMetrics
        return dm.widthPixels / 2f to dm.heightPixels * 0.6f
    }

    // ---------------------------------------------------------------- ADOFAI コントロールバー

    private var rhythmBar: LinearLayout? = null
    private var rhythmLp: WindowManager.LayoutParams? = null
    private var rhythmText: TextView? = null
    private var rhythmPlayBtn: TextView? = null
    private val rhythmTick = object : Runnable {
        override fun run() {
            updateRhythmText()
            main.postDelayed(this, 100)
        }
    }

    private fun updateRhythmText() {
        val c = RhythmPlayer.chart
        val t = rhythmText ?: return
        val off = RhythmPlayer.totalOffsetMs()
        val offTxt = (if (off >= 0) "+" else "") + off + "ms"
        t.text = when {
            c == null -> "コース未選択"
            RhythmPlayer.playing.value -> "自動中 ${RhythmPlayer.index}/${c.times.size}  補正$offTxt"
            RhythmPlayer.lastError != null -> RhythmPlayer.lastError
            RhythmPlayer.index >= c.times.size && c.times.isNotEmpty() -> "完了  補正$offTxt"
            else -> "${c.title.take(14)}  補正$offTxt"
        }
        rhythmPlayBtn?.text = if (RhythmPlayer.playing.value) "■" else "▶"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showRhythmBar() {
        if (rhythmBar != null) return
        val d = density
        fun btn(label: String, color: Int = 0xFF2A323B.toInt(), onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = (40 * d).roundToInt()
            val p = (8 * d).roundToInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply { cornerRadius = 10 * d; setColor(color) }
            setOnClickListener { onClick() }
        }
        val text = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            val p = (6 * d).roundToInt()
            setPadding(p, 0, p, 0)
            maxWidth = (150 * d).roundToInt()
        }
        fun adjust(delta: Int) {
            val key = RhythmPlayer.courseKey
            Config.setCourseAdjustMs(this, key, Config.courseAdjustMs(key) + delta)
            Engine.configVersion.value = Engine.configVersion.value + 1
            updateRhythmText()
        }
        val play = btn("▶", 0xFF1F9D6B.toInt()) {
            if (RhythmPlayer.playing.value) RhythmPlayer.stop() else RhythmPlayer.start()
            updateRhythmText()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (6 * d).roundToInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(0xE6101418.toInt()) }
            val gap = (3 * d).roundToInt()
            fun add(v: View) = addView(v, LinearLayout.LayoutParams(-2, -2).apply { setMargins(gap, 0, gap, 0) })
            add(text)
            add(play)
            add(btn("−") { adjust(-Config.rhythmStepMs) })
            add(btn("+") { adjust(Config.rhythmStepMs) })
            add(btn("✕") {
                RhythmPlayer.stop()
                RhythmPlayer.barVisible = false
                refreshOverlays()
            })
        }
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            x = (8 * d).roundToInt()
            y = (8 * d).roundToInt()
        }
        // 文字部分をドラッグして移動
        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0
        text.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; sx = lp.x; sy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - downX).roundToInt()
                    lp.y = sy + (e.rawY - downY).roundToInt()
                    rhythmBar?.let { wm.updateViewLayout(it, lp) }
                }
            }
            true
        }
        wm.addView(root, lp)
        rhythmBar = root; rhythmLp = lp; rhythmText = text; rhythmPlayBtn = play
        main.post(rhythmTick)
    }

    private fun removeRhythmBar() {
        main.removeCallbacks(rhythmTick)
        rhythmBar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        rhythmBar = null; rhythmLp = null; rhythmText = null; rhythmPlayBtn = null
    }

    // ---------------------------------------------------------------- オーバーレイ共通

    private fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /** 状態に合わせてオーバーレイを出し入れする。メインスレッドで呼ぶこと。 */
    fun refreshOverlays() {
        if (instance == null) return
        val running = Engine.running.value
        val wantMarker = editing || (running && Config.showMarker && Config.tapX >= 0)
        if (wantMarker) showMarker(touchable = editing) else removeMarker()
        if (editing) showPanel() else removePanel()
        if (running && Config.showBubble && !editing) showBubble() else removeBubble()
        if (RhythmPlayer.barVisible && !editing) showRhythmBar() else removeRhythmBar()
        bubble?.invalidate()
        marker?.invalidate()
    }

    // ---------------------------------------------------------------- 位置合わせ

    fun startEditing() {
        editing = true
        refreshOverlays()
    }

    private fun finishEditing(save: Boolean) {
        val m = marker
        if (save && m != null) {
            val loc = IntArray(2)
            m.getLocationOnScreen(loc)
            Config.tapX = loc[0] + m.width / 2f
            Config.tapY = loc[1] + m.height / 2f
            Config.save(this)
            Engine.configVersion.value = Engine.configVersion.value + 1
        }
        editing = false
        // 触れる/触れないウィンドウを切り替えるため作り直す
        removeMarker()
        refreshOverlays()
    }

    private val markerSize get() = (72 * density).roundToInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showMarker(touchable: Boolean) {
        val existing = marker
        if (existing != null && existing.touchable == touchable) return
        removeMarker()
        val s = markerSize
        val dm = resources.displayMetrics
        val cx = if (Config.tapX >= 0) Config.tapX else dm.widthPixels / 2f
        val cy = if (Config.tapY >= 0) Config.tapY else dm.heightPixels / 2f
        val lp = overlayParams(s, s, touchable).apply {
            x = (cx - s / 2f).roundToInt()
            y = (cy - s / 2f).roundToInt()
        }
        val v = MarkerView(this, touchable)
        if (touchable) {
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = startX + (e.rawX - downX).roundToInt()
                        lp.y = startY + (e.rawY - downY).roundToInt()
                        wm.updateViewLayout(v, lp)
                        updatePanelCoords()
                    }
                }
                true
            }
        }
        wm.addView(v, lp)
        marker = v
        markerLp = lp
        // ウィンドウ座標と画面座標のずれ (カットアウト等) を 1 回だけ補正して、見た目とタップ位置を一致させる
        v.post {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val dx = (cx - s / 2f).roundToInt() - loc[0]
            val dy = (cy - s / 2f).roundToInt() - loc[1]
            if ((dx != 0 || dy != 0) && marker === v) {
                lp.x += dx; lp.y += dy
                wm.updateViewLayout(v, lp)
            }
            updatePanelCoords()
        }
    }

    private fun nudge(dx: Int, dy: Int) {
        val v = marker ?: return
        val lp = markerLp ?: return
        lp.x += dx; lp.y += dy
        wm.updateViewLayout(v, lp)
        v.post { updatePanelCoords() }
    }

    private fun removeMarker() {
        marker?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        marker = null
        markerLp = null
    }

    private fun updatePanelCoords() {
        val m = marker ?: return
        val loc = IntArray(2)
        m.getLocationOnScreen(loc)
        panelCoords?.text = "照準をドラッグ → ✓   X ${loc[0] + m.width / 2}  Y ${loc[1] + m.height / 2}"
    }

    private fun showPanel() {
        if (panel != null) return
        val d = density
        fun btn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val p = (10 * d).roundToInt()
            setPadding(p + p / 2, p, p + p / 2, p)
            background = GradientDrawable().apply {
                cornerRadius = 12 * d; setColor(0xFF2A323B.toInt())
            }
            setOnClickListener { onClick() }
        }
        val step = max(1, (density).roundToInt()) // 1dp ずつ微調整
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val gap = (4 * d).roundToInt()
            fun add(v: View) = addView(v, LinearLayout.LayoutParams(-2, -2).apply { setMargins(gap, 0, gap, 0) })
            add(btn("◀") { nudge(-step, 0) })
            add(btn("▲") { nudge(0, -step) })
            add(btn("▼") { nudge(0, step) })
            add(btn("▶") { nudge(step, 0) })
            add(btn("⇅") { movePanel() })
            add(btn("✕") { finishEditing(save = false) })
            add(btn("✓ 決定") { finishEditing(save = true) }.apply {
                (background as GradientDrawable).setColor(0xFF1F9D6B.toInt())
            })
        }
        val coords = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6 * d).roundToInt())
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (10 * d).roundToInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(0xE6101418.toInt()) }
            addView(coords)
            addView(row)
        }
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * d).roundToInt()
        }
        wm.addView(root, lp)
        panel = root; panelLp = lp; panelCoords = coords
        updatePanelCoords()
    }

    private fun movePanel() {
        val p = panel ?: return
        val lp = panelLp ?: return
        lp.gravity = if (lp.gravity and Gravity.BOTTOM == Gravity.BOTTOM) {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        } else {
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        wm.updateViewLayout(p, lp)
    }

    private fun removePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; panelLp = null; panelCoords = null
    }

    // ---------------------------------------------------------------- フローティングボタン

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val d = density
        val lp = overlayParams((168 * d).roundToInt(), (46 * d).roundToInt(), true).apply {
            x = (12 * d).roundToInt()
            y = (120 * d).roundToInt()
        }
        val v = BubbleView(this)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var longPressed = false
        val longPress = Runnable {
            longPressed = true
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            DetectorService.instance?.stopDetection()
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    dragging = false; longPressed = false
                    main.postDelayed(longPress, 700)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (dragging && bubble === v) {
                        lp.x = startX + (e.rawX - downX).roundToInt()
                        lp.y = startY + (e.rawY - downY).roundToInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!dragging && !longPressed) Engine.setArmed(!Engine.armed.value)
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            true
        }
        wm.addView(v, lp)
        bubble = v; bubbleLp = lp
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null; bubbleLp = null
    }
}

/** タップ位置の照準。編集中はオレンジで操作可、実行中は半透明の緑で素通し。 */
@SuppressLint("ViewConstructor")
class MarkerView(ctx: Context, val touchable: Boolean) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var flashAt = 0L

    fun flash() {
        flashAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2
        val d = resources.displayMetrics.density
        val color = if (touchable) 0xFFFF8A3D.toInt() else 0x993DDC97.toInt()
        val since = SystemClock.uptimeMillis() - flashAt
        if (since < 220) {
            fill.color = Color.argb((200 * (1 - since / 220f)).toInt(), 255, 255, 255)
            c.drawCircle(cx, cy, min(cx, cy) - 2 * d, fill)
            postInvalidateOnAnimation()
        }
        if (touchable) {
            fill.color = 0x33FF8A3D
            c.drawCircle(cx, cy, min(cx, cy) - 2 * d, fill)
        }
        ring.color = color
        ring.strokeWidth = 2.5f * d
        c.drawCircle(cx, cy, min(cx, cy) - 3 * d, ring)
        ring.strokeWidth = 1.5f * d
        val gap = 5 * d
        c.drawLine(cx, 4 * d, cx, cy - gap, ring)
        c.drawLine(cx, cy + gap, cx, h - 4 * d, ring)
        c.drawLine(4 * d, cy, cx - gap, cy, ring)
        c.drawLine(cx + gap, cy, w - 4 * d, cy, ring)
        fill.color = color
        c.drawCircle(cx, cy, 1.6f * d, fill)
    }
}

/** 画面上に浮かぶ小さな操作ボタン: タップで一時停止/再開、長押しで停止、ドラッグで移動。 */
class BubbleView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * ctx.resources.displayMetrics.density
        isFakeBoldText = true
    }
    private val rect = RectF()
    private var flashAt = 0L
    private val tick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 33) }
    }

    fun flash() { flashAt = SystemClock.uptimeMillis() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    private fun norm(db: Float) = ((db + 80f) / 80f).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        val armed = Engine.armed.value
        val flashing = SystemClock.uptimeMillis() - flashAt < 150
        rect.set(0f, 0f, w, h)
        p.color = if (flashing) 0xF01F9D6B.toInt() else 0xE6101418.toInt()
        c.drawRoundRect(rect, h / 2, h / 2, p)

        // 状態ランプ
        p.color = when {
            !Engine.tapServiceConnected.value -> 0xFFE5484D.toInt()
            armed -> 0xFF3DDC97.toInt()
            else -> 0xFF7A838C.toInt()
        }
        c.drawCircle(h / 2, h / 2, 7 * d, p)

        // レベルメーター + しきい値
        val left = h
        val right = w - 46 * d
        val top = h / 2 - 4 * d
        val bottom = h / 2 + 4 * d
        rect.set(left, top, right, bottom)
        p.color = 0xFF2A323B.toInt()
        c.drawRoundRect(rect, 4 * d, 4 * d, p)
        val lvl = norm(Engine.levelDb)
        val thr = norm(Engine.effectiveThresholdDb)
        rect.set(left, top, left + (right - left) * lvl, bottom)
        p.color = if (lvl >= thr) 0xFFFFB547.toInt() else 0xFF3DDC97.toInt()
        c.drawRoundRect(rect, 4 * d, 4 * d, p)
        p.color = 0xFFE5484D.toInt()
        val tx = left + (right - left) * thr
        c.drawRect(tx - 1 * d, top - 4 * d, tx + 1 * d, bottom + 4 * d, p)

        val label = if (armed) "×${Engine.triggerCount.value}" else "休止"
        c.drawText(label, right + 8 * d, h / 2 + text.textSize / 3, text)
    }
}
