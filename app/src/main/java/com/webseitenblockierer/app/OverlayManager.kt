package com.webseitenblockierer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Draws / removes the two on-top views the blocker uses:
 *  - the full-screen *block overlay* shown when a blocked site is actually opened,
 *  - a small, unobtrusive *countdown banner* shown while a timed free window is
 *    running, telling the user how much time is left before the site locks again.
 *
 * This is the native counterpart of the extension's content.js overlay.
 */
class OverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val store = BlockStore(context)
    private val credit = CreditStore(context)
    private val handler = Handler(Looper.getMainLooper())

    // --- Block overlay ---------------------------------------------------
    private var overlayView: View? = null
    private var shownForHost: String? = null

    // --- Countdown banner ------------------------------------------------
    private var countdownView: View? = null
    private var countdownText: TextView? = null
    private var countdownHost: String? = null
    private var countdownTick: Runnable? = null

    val isShowing: Boolean get() = overlayView != null

    /** Whether the block overlay is currently shown for this exact host. */
    fun isShowingFor(host: String): Boolean = overlayView != null && shownForHost == host

    // =====================================================================
    // Block overlay
    // =====================================================================

    fun show(host: String, browserPackage: String? = null) {
        // Showing the block overlay always supersedes the countdown for that host.
        hideCountdown()

        // Already showing for this host — nothing to do.
        if (isShowingFor(host)) return
        // Showing for a different host — rebuild.
        if (overlayView != null) remove()

        // Without credit there is no text input, so the overlay must NOT grab focus:
        // a focusable, opaque, full-screen window swallows Home/Back and can strand
        // the user behind it (e.g. if the accessibility service later misses the
        // event that would remove it). Only take focus when seconds can be entered.
        val balance = credit.balance()
        val root = buildOverlay(host, balance, browserPackage)

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (balance <= 0) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.OPAQUE
        )
        params.gravity = Gravity.CENTER

        try {
            windowManager.addView(root, params)
            overlayView = root
            shownForHost = host
        } catch (e: Exception) {
            // If we lack the overlay permission this throws; fail silently.
        }
    }

    fun remove() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        shownForHost = null
    }

    private fun buildOverlay(host: String, balance: Int, browserPackage: String?): View {
        // Custom root so we can intercept the Back key on the whole overlay window.
        // A plain OnKeyListener only fires for the focused child (the EditText), so
        // it would miss Back; dispatchKeyEvent on the window root sees every key.
        // Keeping Back working is the emergency escape: it dismisses the overlay so
        // the user is never held on the block screen. The site stays blocked and a
        // fresh navigation to it shows the block again.
        val root = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP
                ) {
                    remove()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FC000000"))
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(context).apply {
            text = host
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        root.addView(title)

        val balanceText = TextView(context).apply {
            text = context.getString(R.string.credit_balance, balance)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        root.addView(balanceText)

        if (balance <= 0) {
            val msg = TextView(context).apply {
                text = context.getString(R.string.overlay_no_credit)
                setTextColor(Color.LTGRAY)
                textSize = 16f
                gravity = Gravity.CENTER
            }
            root.addView(msg)
            root.addView(buildTrainingButton())
            // Always give the user a way out — otherwise a full-screen overlay
            // without input is an inescapable trap.
            root.addView(buildCloseButton(browserPackage))
            return root
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = context.getString(R.string.overlay_seconds_hint, balance)
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 16f
            setPadding(24, 24, 24, 24)
            minWidth = 240
        }
        row.addView(input)

        val button = Button(context).apply {
            text = context.getString(R.string.overlay_allow_button)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = 24
            layoutParams = lp
            setOnClickListener {
                val seconds = input.text.toString().toIntOrNull()
                if (seconds == null || seconds <= 0) {
                    Toast.makeText(
                        context,
                        "Bitte gib eine gültige Anzahl von Sekunden ein.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                // Pay with credit; the balance may have changed since the overlay
                // was built, so spend() re-checks it.
                if (!credit.spend(seconds)) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.overlay_not_enough, credit.balance()),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                store.allowFor(host, seconds)
                remove()
            }
        }
        row.addView(button)

        root.addView(row)
        root.addView(buildTrainingButton())
        // A guaranteed escape hatch: leave the blocked page without allowing it.
        root.addView(buildCloseButton(browserPackage))
        return root
    }

    /** Opens the training screen, where exercise reps earn more seconds. */
    private fun buildTrainingButton(): View = Button(context).apply {
        text = context.getString(R.string.overlay_training_button)
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.rgb(240, 255, 105))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = 48
        layoutParams = lp
        setOnClickListener {
            remove()
            try {
                context.startActivity(
                    Intent(context, TrainingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    /**
     * An "Andere Seite öffnen" button that removes the overlay and navigates the
     * *same* browser to a neutral page, so the user leaves the blocked page while
     * staying in the browser — and, crucially, the browser's last page is no longer
     * the blocked one, so reopening it does not land straight back on the block.
     *
     * It runs entirely within the overlay view, so it works even if the
     * accessibility service is no longer processing events — the user can never be
     * stuck behind the overlay. The site stays blocked: returning to it re-shows it.
     */
    private fun buildCloseButton(browserPackage: String?): View = Button(context).apply {
        text = context.getString(R.string.overlay_close_button)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#333333"))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = 48
        layoutParams = lp
        setOnClickListener {
            remove()
            openNeutralPage(browserPackage)
        }
    }

    /**
     * Open a neutral page in [browserPackage] (the browser the block was shown in),
     * replacing the blocked page. Falls back to the default browser and, if even
     * that fails, to the home screen, so there is always a way off the page.
     */
    private fun openNeutralPage(browserPackage: String?) {
        val uri = Uri.parse(context.getString(R.string.neutral_page_url))
        val view = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Prefer the same browser so the user stays where they were.
        if (browserPackage != null) {
            try {
                context.startActivity(Intent(view).setPackage(browserPackage))
                return
            } catch (_: Exception) {
                // The browser could not be targeted directly (e.g. package not
                // visible / no matching activity) — fall through to the default.
            }
        }
        try {
            context.startActivity(view)
        } catch (_: Exception) {
            goHome()
        }
    }

    /** Last-resort exit: send the user to the launcher, off the blocked page. */
    private fun goHome() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    // =====================================================================
    // Countdown banner
    // =====================================================================

    /**
     * Show (or keep showing) a small banner counting down the remaining time of the
     * active free window for [host]. Updates itself every second and removes itself
     * when the window has elapsed. A no-op if no free window is active.
     */
    fun showCountdown(host: String) {
        if (store.allowSecondsRemaining(host) <= 0) {
            hideCountdown()
            return
        }

        // Already counting down for this host — the running tick keeps it fresh.
        if (countdownView != null && countdownHost == host) return
        // Different host — rebuild.
        if (countdownView != null) hideCountdown()

        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(36, 20, 36, 20)
            setBackgroundColor(Color.parseColor("#CC000000"))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(text)
        }

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // The banner must never grab input: the user keeps browsing normally
            // underneath it while the timer runs.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 24

        try {
            windowManager.addView(root, params)
            countdownView = root
            countdownText = text
            countdownHost = host
            startCountdownTicks(host)
        } catch (e: Exception) {
            // Missing overlay permission — fail silently.
        }
    }

    private fun startCountdownTicks(host: String) {
        countdownTick?.let { handler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (countdownHost != host || countdownView == null) return
                val remaining = store.allowSecondsRemaining(host)
                if (remaining <= 0) {
                    hideCountdown()
                    return
                }
                countdownText?.text = formatCountdown(remaining)
                handler.postDelayed(this, 1000L)
            }
        }
        countdownTick = tick
        handler.post(tick)
    }

    fun hideCountdown() {
        countdownTick?.let { handler.removeCallbacks(it) }
        countdownTick = null
        countdownView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        countdownView = null
        countdownText = null
        countdownHost = null
    }

    /** Remove everything this manager owns (e.g. when leaving the browser). */
    fun removeAll() {
        remove()
        hideCountdown()
    }

    /** Format e.g. 522 seconds as "Noch 08:42 Minuten". */
    private fun formatCountdown(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return context.getString(R.string.countdown_remaining, minutes, seconds)
    }
}
