package com.webseitenblockierer.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Shared look of the nur10 screens (dark, like the original web app). */
object Ui {
    val BACKGROUND = Color.rgb(15, 18, 28)
    val SURFACE = Color.rgb(24, 29, 42)
    val TEXT = Color.argb(242, 255, 255, 255)
    val TEXT_DIM = Color.argb(184, 223, 228, 242)
    val ACCENT = Color.rgb(240, 255, 105)

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    fun title(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextColor(TEXT)
        textSize = 22f
        setPadding(0, 0, 0, context.dp(12))
    }

    fun hint(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextColor(TEXT_DIM)
        textSize = 13f
        setPadding(0, context.dp(4), 0, context.dp(4))
    }

    fun column(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(20), context.dp(24), context.dp(20), context.dp(24))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
