package com.springcat.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The engine of the auto-clicker.
 *
 * An AccessibilityService is the only way to inject taps into other apps
 * without root. Taps are performed with [dispatchGesture] (API 24+), which is
 * available on Fire OS 6, 7 and 8.
 *
 * The floating UI (control panel + draggable target) is drawn with a
 * TYPE_ACCESSIBILITY_OVERLAY window. That window type belongs to the
 * accessibility service itself, so it needs NO "draw over other apps"
 * permission — which is exactly why it behaves consistently on Fire tablets.
 */
class ClickerService : AccessibilityService() {

    companion object {
        /** Live reference so the UI can query whether the service is connected. */
        @Volatile
        var instance: ClickerService? = null
            private set

        const val MIN_INTERVAL_MS = 50L
        const val MAX_INTERVAL_MS = 10_000L
        const val TAP_DURATION_MS = 40L
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var panelView: View? = null
    private var targetView: View? = null
    private var targetParams: WindowManager.LayoutParams? = null

    private var running = false
    private var intervalMs = 1000L

    // Center of the tap target, in absolute screen pixels.
    private var targetX = 0
    private var targetY = 0

    private val clickRunnable = object : Runnable {
        override fun run() {
            performTap(targetX.toFloat(), targetY.toFloat())
            if (running) handler.postDelayed(this, intervalMs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val prefs = getSharedPreferences("clicker", Context.MODE_PRIVATE)
        intervalMs = prefs.getLong("interval", 1000L)

        val metrics = resources.displayMetrics
        targetX = prefs.getInt("targetX", metrics.widthPixels / 2)
        targetY = prefs.getInt("targetY", metrics.heightPixels / 2)

        showTarget()
        showPanel()
        refreshPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }

    override fun onInterrupt() { /* not used */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopClicking()
        removeOverlays()
        instance = null
        return super.onUnbind(intent)
    }

    // ---------------------------------------------------------------------
    // Tap injection
    // ---------------------------------------------------------------------

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun startClicking() {
        if (running) return
        running = true
        // While clicking, make the target pass-through so our own overlay does
        // not swallow the dispatched taps.
        setTargetTouchable(false)
        handler.post(clickRunnable)
        refreshPanel()
    }

    private fun stopClicking() {
        running = false
        handler.removeCallbacks(clickRunnable)
        setTargetTouchable(true)
        refreshPanel()
    }

    // ---------------------------------------------------------------------
    // Overlay: draggable target marker
    // ---------------------------------------------------------------------

    private fun showTarget() {
        val marker = ImageView(this).apply {
            setImageResource(R.drawable.ic_target)
        }
        val size = dp(64)
        val params = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = targetX - size / 2
            y = targetY - size / 2
        }
        // Drag the marker to set the tap point (only while stopped).
        marker.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (e.rawX - downX).roundToInt()
                        params.y = startY + (e.rawY - downY).roundToInt()
                        windowManager.updateViewLayout(v, params)
                        targetX = params.x + size / 2
                        targetY = params.y + size / 2
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        saveState()
                        return true
                    }
                }
                return false
            }
        })
        targetView = marker
        targetParams = params
        windowManager.addView(marker, params)
    }

    private fun setTargetTouchable(touchable: Boolean) {
        val p = targetParams ?: return
        val v = targetView ?: return
        p.flags = if (touchable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        windowManager.updateViewLayout(v, p)
    }

    // ---------------------------------------------------------------------
    // Overlay: control panel
    // ---------------------------------------------------------------------

    private fun showPanel() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        val params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(80)
        }

        view.findViewById<View>(R.id.btnStartStop).setOnClickListener {
            if (running) stopClicking() else startClicking()
        }
        view.findViewById<View>(R.id.btnMinus).setOnClickListener {
            intervalMs = (intervalMs - stepFor(intervalMs)).coerceAtLeast(MIN_INTERVAL_MS)
            saveState(); refreshPanel()
        }
        view.findViewById<View>(R.id.btnPlus).setOnClickListener {
            intervalMs = (intervalMs + stepFor(intervalMs)).coerceAtMost(MAX_INTERVAL_MS)
            saveState(); refreshPanel()
        }

        // Drag the whole panel by its handle.
        view.findViewById<View>(R.id.dragHandle).setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (e.rawX - downX).roundToInt()
                        params.y = startY + (e.rawY - downY).roundToInt()
                        windowManager.updateViewLayout(view, params)
                        return true
                    }
                }
                return false
            }
        })

        panelView = view
        windowManager.addView(view, params)
    }

    private fun refreshPanel() {
        val view = panelView ?: return
        view.findViewById<TextView>(R.id.txtInterval).text =
            getString(R.string.interval_fmt, intervalMs)
        val btn = view.findViewById<TextView>(R.id.btnStartStop)
        btn.text = getString(if (running) R.string.stop else R.string.start)
        btn.setBackgroundResource(
            if (running) R.drawable.bg_btn_stop else R.drawable.bg_btn_start
        )
    }

    private fun removeOverlays() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        targetView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        targetView = null
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun stepFor(v: Long): Long = when {
        v < 200 -> 10
        v < 1000 -> 50
        v < 3000 -> 250
        else -> 500
    }

    private fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
        return WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun saveState() {
        getSharedPreferences("clicker", Context.MODE_PRIVATE).edit()
            .putLong("interval", intervalMs)
            .putInt("targetX", targetX)
            .putInt("targetY", targetY)
            .apply()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
