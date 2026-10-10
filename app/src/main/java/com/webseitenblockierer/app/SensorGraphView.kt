package com.webseitenblockierer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Live curve of the sensor value (like nur10's canvas view): the signal, the
 * resting position, both counting thresholds and a marker for every counted rep.
 */
class SensorGraphView(context: Context) : View(context) {

    companion object {
        private const val SAMPLES = 300
    }

    private val values = FloatArray(SAMPLES)
    private val baselines = FloatArray(SAMPLES)
    private val lows = FloatArray(SAMPLES)
    private val highs = FloatArray(SAMPLES)
    private val reps = BooleanArray(SAMPLES)
    private var size = 0
    private var head = 0 // index of the next write
    private var pendingRep = false

    private val density = resources.displayMetrics.density
    private val signalPaint = paint(Color.WHITE, 2.5f)
    private val baselinePaint = paint(Color.argb(140, 255, 255, 255), 1f)
    private val thresholdPaint = paint(Color.rgb(240, 255, 105), 1.5f)
    private val repPaint = paint(Color.rgb(110, 255, 217), 2f)
    private val path = Path()

    fun addSample(value: Float, baseline: Float, low: Float, high: Float) {
        values[head] = value
        baselines[head] = baseline
        lows[head] = low
        highs[head] = high
        reps[head] = pendingRep
        pendingRep = false
        head = (head + 1) % SAMPLES
        if (size < SAMPLES) size++
        invalidate()
    }

    /** Mark the next sample as the moment a rep was counted. */
    fun markRep() {
        pendingRep = true
    }

    fun clear() {
        size = 0
        head = 0
        pendingRep = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (size < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        val newest = (head - 1 + SAMPLES) % SAMPLES

        // Scale around the current resting position so small movements stay visible.
        val center = baselines[newest]
        var range = max(abs(highs[newest] - center), abs(center - lows[newest])) * 2.5f
        for (i in 0 until size) {
            range = max(range, abs(values[(head - size + i + SAMPLES) % SAMPLES] - center) * 1.1f)
        }
        range = max(range, 0.5f)
        fun y(v: Float) = h / 2 - (v - center) / range * (h / 2)
        val step = w / (SAMPLES - 1)
        val startX = w - (size - 1) * step

        drawSeries(canvas, baselines, startX, step, ::y, baselinePaint)
        drawSeries(canvas, lows, startX, step, ::y, thresholdPaint)
        drawSeries(canvas, highs, startX, step, ::y, thresholdPaint)
        drawSeries(canvas, values, startX, step, ::y, signalPaint)

        for (i in 0 until size) {
            if (reps[(head - size + i + SAMPLES) % SAMPLES]) {
                val x = startX + i * step
                canvas.drawLine(x, 0f, x, h, repPaint)
            }
        }
    }

    private fun drawSeries(
        canvas: Canvas,
        series: FloatArray,
        startX: Float,
        step: Float,
        y: (Float) -> Float,
        paint: Paint
    ) {
        path.reset()
        for (i in 0 until size) {
            val v = series[(head - size + i + SAMPLES) % SAMPLES]
            val x = startX + i * step
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        canvas.drawPath(path, paint)
    }

    private fun paint(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthDp * density
    }
}
