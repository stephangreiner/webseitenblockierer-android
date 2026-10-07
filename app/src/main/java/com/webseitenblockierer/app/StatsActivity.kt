package com.webseitenblockierer.app

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar
import kotlin.math.roundToInt

/** Monthly statistics: reps per day and exercise, month total and daily average. */
class StatsActivity : AppCompatActivity() {

    companion object {
        private val BACKGROUND = Color.rgb(15, 18, 28)
        private val TEXT = Color.argb(242, 255, 255, 255)
        private val TEXT_DIM = Color.argb(184, 223, 228, 242)
        private val MONTHS = listOf(
            "Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez"
        )
    }

    private lateinit var stats: ExerciseStats
    private lateinit var monthTitle: TextView
    private lateinit var nextButton: Button
    private lateinit var table: TableLayout

    private var year = 0
    private var month = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stats = ExerciseStats(this)
        val now = Calendar.getInstance()
        year = now.get(Calendar.YEAR)
        month = now.get(Calendar.MONTH)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = "‹"
            setOnClickListener { navigate(-1) }
        })
        monthTitle = TextView(this).apply {
            setTextColor(TEXT)
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(monthTitle)
        nextButton = Button(this).apply {
            text = "›"
            setOnClickListener { navigate(1) }
        }
        header.addView(nextButton)
        column.addView(header)

        table = TableLayout(this).apply {
            isStretchAllColumns = true
            setPadding(0, dp(12), 0, 0)
        }
        column.addView(table)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(column)
        })
        render()
    }

    private fun navigate(delta: Int) {
        val cal = Calendar.getInstance().apply { set(year, month, 1) }
        cal.add(Calendar.MONTH, delta)
        year = cal.get(Calendar.YEAR)
        month = cal.get(Calendar.MONTH)
        render()
    }

    private fun render() {
        val now = Calendar.getInstance()
        val isCurrentMonth = year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH)
        nextButton.isEnabled = !isCurrentMonth
        monthTitle.text = "${MONTHS[month]} $year"

        val daysInMonth = Calendar.getInstance().apply { set(year, month, 1) }
            .getActualMaximum(Calendar.DAY_OF_MONTH)
        // In the running month only the days so far count, newest first.
        val lastDay = if (isCurrentMonth) now.get(Calendar.DAY_OF_MONTH) else daysInMonth
        val exercises = Exercise.values()

        table.removeAllViews()

        val head = TableRow(this)
        head.addView(cell(getString(R.string.stats_day), bold = true))
        for (ex in exercises) {
            head.addView(ImageView(this).apply {
                setImageResource(ex.iconRes)
                contentDescription = ex.label
                layoutParams = TableRow.LayoutParams(dp(32), dp(32))
            })
        }
        table.addView(head)

        val totals = IntArray(exercises.size)
        val days = if (isCurrentMonth) (lastDay downTo 1) else (1..lastDay)
        for (day in days) {
            val row = TableRow(this)
            row.addView(cell("$day. ${MONTHS[month]}", dim = true))
            exercises.forEachIndexed { i, ex ->
                val value = stats.get(year, month, day, ex)
                totals[i] += value
                row.addView(cell(if (value == 0) "–" else value.toString(), color = ex.color))
            }
            table.addView(row)
        }

        val totalRow = TableRow(this)
        totalRow.addView(cell(getString(R.string.stats_total, MONTHS[month]), bold = true))
        exercises.forEachIndexed { i, ex ->
            totalRow.addView(cell(totals[i].toString(), color = ex.color, bold = true))
        }
        table.addView(totalRow)

        val avgRow = TableRow(this)
        avgRow.addView(cell("Ø", bold = true))
        exercises.forEachIndexed { i, ex ->
            avgRow.addView(
                cell((totals[i].toDouble() / lastDay).roundToInt().toString(), color = ex.color)
            )
        }
        table.addView(avgRow)
    }

    private fun cell(
        value: String,
        color: Int = TEXT,
        bold: Boolean = false,
        dim: Boolean = false
    ) = TextView(this).apply {
        text = value
        setTextColor(if (dim) TEXT_DIM else color)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(6), dp(4), dp(6))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
