package com.webseitenblockierer.app

import android.content.Context
import java.util.Calendar
import java.util.Locale

/** Daily rep counts per exercise, keyed by calendar day (like nur10's statistics). */
class ExerciseStats(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nur10_statistik", Context.MODE_PRIVATE)

    fun increment(exercise: Exercise) {
        val key = key(Calendar.getInstance(), exercise)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    /** Reps of [exercise] on [day] (1-based) of [month] (0-based) in [year]. */
    fun get(year: Int, month: Int, day: Int, exercise: Exercise): Int {
        val cal = Calendar.getInstance().apply { set(year, month, day) }
        return prefs.getInt(key(cal, exercise), 0)
    }

    fun today(exercise: Exercise): Int = prefs.getInt(key(Calendar.getInstance(), exercise), 0)

    private fun key(cal: Calendar, exercise: Exercise): String =
        String.format(
            Locale.US, "%04d-%02d-%02d|%s",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH), exercise.key
        )
}
