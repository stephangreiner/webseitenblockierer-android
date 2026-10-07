package com.webseitenblockierer.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "Punktekonto": every exercise rep earns one second of time on a blocked
 * site. The balance only lives for the current day — at midnight it expires.
 */
class CreditStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "guthaben"
        private const val KEY_BALANCE = "balanceSeconds"
        private const val KEY_DAY = "day"
    }

    /** Seconds available today (0 if the stored balance is from an earlier day). */
    fun balance(): Int =
        if (prefs.getString(KEY_DAY, null) == today()) prefs.getInt(KEY_BALANCE, 0) else 0

    fun add(seconds: Int) {
        if (seconds <= 0) return
        save(balance() + seconds)
    }

    /** Deduct [seconds] from the balance. Returns false if there is not enough. */
    fun spend(seconds: Int): Boolean {
        val current = balance()
        if (seconds <= 0 || seconds > current) return false
        save(current - seconds)
        return true
    }

    private fun save(balance: Int) {
        prefs.edit()
            .putString(KEY_DAY, today())
            .putInt(KEY_BALANCE, balance)
            .apply()
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
