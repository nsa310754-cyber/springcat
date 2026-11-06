package site.ragdollp.rvnc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * PC の画面を描画する View。
 *  - 2 本指でピンチ & ドラッグ
 *  - 1 本指タップ = 左クリック, 長押しドラッグ = 左ドラッグ
 *  - 2 本指タップ = 右クリック (放した瞬間)
 *  - 3 本指タップ = 中クリック
 *
 * スマホ座標 → PC 座標の変換は [Matrix] で行う。
 */
class VncView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }

    /** 入力イベントを VNC 側へ飛ばすコールバック (vncX, vncY, buttonMask) */
    var onPointer: ((Int, Int, Int) -> Unit)? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val mat = Matrix()
    private val inv = Matrix()
    private var fit = false

    private val scaleGd: ScaleGestureDetector
    private var scale = 1f
    private var tx = 0f; private var ty = 0f
    private var pressed = false

    init {
        isFocusable = true; isFocusableInTouchMode = true
        scaleGd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val s = d.scaleFactor
                val fx = d.focusX; val fy = d.focusY
                scale = (scale * s).coerceIn(0.2f, 6f)
                // フォーカス中心をずらさないように tx/ty を補正
                tx = fx - (fx - tx) * s
                ty = fy - (fy - ty) * s
                updateMat()
                return true
            }
        })
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit = false
        updateMat()
    }

    private fun fitOnce() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val sx = width.toFloat() / bmp.width
        val sy = height.toFloat() / bmp.height
        scale = min(sx, sy)
        tx = (width - bmp.width * scale) / 2f
        ty = (height - bmp.height * scale) / 2f
        fit = true
    }

    private fun updateMat() {
        val bmp = bitmap ?: return
        if (!fit) fitOnce()
        mat.reset()
        mat.postScale(scale, scale)
        mat.postTranslate(tx, ty)
        mat.invert(inv)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (!fit) { fitOnce(); updateMat() }
        canvas.drawBitmap(bmp, mat, paint)
    }

    private val tmp = FloatArray(2)
    private fun toVnc(sx: Float, sy: Float): Pair<Int, Int> {
        tmp[0] = sx; tmp[1] = sy
        inv.mapPoints(tmp)
        val bmp = bitmap ?: return 0 to 0
        return tmp[0].toInt().coerceIn(0, bmp.width - 1) to
               tmp[1].toInt().coerceIn(0, bmp.height - 1)
    }

    private var lastVx = 0; private var lastVy = 0
    private var panLastX = 0f; private var panLastY = 0f
    private var mode = Mode.NONE
    private enum class Mode { NONE, TAP, PAN, RIGHT, MIDDLE }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGd.onTouchEvent(event)
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                panLastX = event.x; panLastY = event.y
                mode = Mode.TAP
                val (vx, vy) = toVnc(event.x, event.y)
                lastVx = vx; lastVy = vy
                onPointer?.invoke(vx, vy, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                when (event.pointerCount) {
                    2 -> mode = Mode.RIGHT   // 2 本指: 右クリック予定
                    3 -> mode = Mode.MIDDLE  // 3 本指: 中クリック予定
                    else -> mode = Mode.PAN
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleGd.isInProgress) return true
                if (event.pointerCount == 1 && mode == Mode.TAP) {
                    // 1 本指移動: カーソル移動 (左ボタン押していない状態)
                    val (vx, vy) = toVnc(event.x, event.y)
                    lastVx = vx; lastVy = vy
                    onPointer?.invoke(vx, vy, 0)
                } else if (event.pointerCount >= 2) {
                    // 2 本指ドラッグ: パン
                    val dx = event.x - panLastX
                    val dy = event.y - panLastY
                    tx += dx; ty += dy
                    panLastX = event.x; panLastY = event.y
                    updateMat()
                }
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.TAP -> {
                        // 左クリック (押す→離す)
                        onPointer?.invoke(lastVx, lastVy, 1)
                        onPointer?.invoke(lastVx, lastVy, 0)
                    }
                    Mode.RIGHT -> {
                        onPointer?.invoke(lastVx, lastVy, 4)
                        onPointer?.invoke(lastVx, lastVy, 0)
                    }
                    Mode.MIDDLE -> {
                        onPointer?.invoke(lastVx, lastVy, 2)
                        onPointer?.invoke(lastVx, lastVy, 0)
                    }
                    else -> {}
                }
                mode = Mode.NONE
            }
        }
        return true
    }
}
