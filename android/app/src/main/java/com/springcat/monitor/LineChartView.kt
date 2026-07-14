package com.springcat.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * A lightweight, dependency-free line chart.
 *
 * Holds one or more [Series], each a bounded ring buffer of samples, and renders them as
 * smooth-ish polylines over a labelled grid. The Y axis can be fixed (e.g. 0..100 for a
 * percentage) or auto-scaling to the largest sample currently on screen.
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** A named data series with its own colour and fixed capacity. */
    class Series(val label: String, val color: Int, val capacity: Int) {
        val values = ArrayDeque<Float>(capacity)

        fun add(value: Float) {
            if (values.size >= capacity) values.removeFirst()
            values.addLast(value)
        }
    }

    private val series = mutableListOf<Series>()

    /** When > 0, the Y axis is fixed to [0, fixedMax]; otherwise it auto-scales. */
    var fixedMax: Float = 0f

    /** Formats a Y-axis gridline value into a label (e.g. "50%"). */
    var yLabelFormatter: (Float) -> String = { v -> v.toInt().toString() }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B3040")
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B90A0")
        textSize = sp(11f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()

    private val padLeft = dp(38f)
    private val padRight = dp(10f)
    private val padTop = dp(10f)
    private val padBottom = dp(18f)

    fun addSeries(s: Series) {
        series.add(s)
        invalidate()
    }

    /** Clears every series' samples (used to reseed history when the screen is reopened). */
    fun clearData() {
        series.forEach { it.values.clear() }
        invalidate()
    }

    /** Pushes one new sample per series (indexed in the order series were added) and repaints. */
    fun push(vararg samples: Float) {
        samples.forEachIndexed { i, v ->
            if (i < series.size) series[i].add(v)
        }
        invalidate()
    }

    private fun currentMax(): Float {
        if (fixedMax > 0f) return fixedMax
        var maxV = 1f
        series.forEach { s -> s.values.forEach { maxV = max(maxV, it) } }
        // Round the auto-scale ceiling up to something readable.
        return niceCeil(maxV)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = padLeft
        val top = padTop
        val right = width - padRight
        val bottom = height - padBottom
        val plotW = right - left
        val plotH = bottom - top
        if (plotW <= 0 || plotH <= 0) return

        val maxV = currentMax()

        // Horizontal gridlines + Y labels at 0, 25, 50, 75, 100 %.
        val steps = 4
        for (i in 0..steps) {
            val frac = i / steps.toFloat()
            val y = bottom - frac * plotH
            canvas.drawLine(left, y, right, y, gridPaint)
            val label = yLabelFormatter(frac * maxV)
            canvas.drawText(label, dp(2f), y + sp(4f), axisTextPaint)
        }

        // Each series as a polyline with a soft fill underneath.
        series.forEach { s ->
            val n = s.values.size
            if (n < 2) return@forEach
            path.reset()
            val stepX = plotW / (s.capacity - 1).toFloat()
            // Right-align the newest sample; older samples trail to the left.
            val startIndex = s.capacity - n
            s.values.forEachIndexed { idx, v ->
                val x = left + (startIndex + idx) * stepX
                val clamped = v.coerceIn(0f, maxV)
                val y = bottom - (clamped / maxV) * plotH
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            // Stroke the line.
            linePaint.color = s.color
            canvas.drawPath(path, linePaint)

            // Fill: close the path down to the baseline and draw translucent.
            val lastX = left + (startIndex + n - 1) * stepX
            val firstX = left + startIndex * stepX
            path.lineTo(lastX, bottom)
            path.lineTo(firstX, bottom)
            path.close()
            fillPaint.color = (s.color and 0x00FFFFFF) or (0x22 shl 24)
            canvas.drawPath(path, fillPaint)
        }
    }

    private fun niceCeil(v: Float): Float {
        if (v <= 10f) return 10f
        val mag = Math.pow(10.0, Math.floor(Math.log10(v.toDouble()))).toFloat()
        val n = v / mag
        val nice = when {
            n <= 1f -> 1f
            n <= 2f -> 2f
            n <= 5f -> 5f
            else -> 10f
        }
        return nice * mag
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun sp(v: Float) =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
        )
}
