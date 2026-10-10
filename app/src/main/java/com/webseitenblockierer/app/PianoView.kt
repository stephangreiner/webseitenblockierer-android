package com.webseitenblockierer.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** One-octave piano keyboard (C to B). Reports the tapped key as semitone 0–11. */
class PianoView(context: Context) : View(context) {

    var onKey: ((semitone: Int) -> Unit)? = null

    private val whiteSemitones = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    /** Black keys: semitone and the white key index they sit after. */
    private val blackKeys = listOf(1 to 0, 3 to 1, 6 to 3, 8 to 4, 10 to 5)
    private var pressed = -1

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textAlign = Paint.Align.CENTER
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val labels = listOf("C", "D", "E", "F", "G", "A", "H")

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val keyW = width / 7f
        val h = height.toFloat()
        for (i in 0 until 7) {
            val rect = RectF(i * keyW, 0f, (i + 1) * keyW, h)
            canvas.drawRect(rect, if (pressed == whiteSemitones[i]) pressedPaint else whitePaint)
            canvas.drawRect(rect, borderPaint)
            canvas.drawText(labels[i], rect.centerX(), h - labelPaint.textSize / 2, labelPaint)
        }
        for ((semitone, after) in blackKeys) {
            canvas.drawRect(blackRect(after, keyW, h), if (pressed == semitone) pressedPaint else blackPaint)
        }
    }

    private fun blackRect(after: Int, keyW: Float, h: Float): RectF {
        val center = (after + 1) * keyW
        return RectF(center - keyW * 0.3f, 0f, center + keyW * 0.3f, h * 0.6f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val keyW = width / 7f
                val h = height.toFloat()
                pressed = blackKeys.firstOrNull { (_, after) ->
                    blackRect(after, keyW, h).contains(event.x, event.y)
                }?.first ?: whiteSemitones[(event.x / keyW).toInt().coerceIn(0, 6)]
                onKey?.invoke(pressed)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
            }
        }
        return true
    }
}
