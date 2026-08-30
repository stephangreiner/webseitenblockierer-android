package com.webseitenblockierer.app

import android.content.Context
import android.content.Intent
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

    fun show(host: String) {
        // Showing the block overlay always supersedes the countdown for that host.
        hideCountdown()

        // Already showing for this host — nothing to do.
        if (isShowingFor(host)) return
        // Showing for a different host — rebuild.
        if (overlayView != null) remove()

        // Cooldown state shows no text input, so the overlay must NOT grab focus:
        // a focusable, opaque, full-screen window swallows Home/Back and can strand
        // the user behind it (e.g. if the accessibility service later misses the
        // event that would remove it). Only take focus for the allow (EditText) mode.
        val cooldown = store.cooldownMinutesRemaining(host)
        val root = buildOverlay(host, cooldown)

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (cooldown > 0) {
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

    private fun buildOverlay(host: String, cooldown: Int): View {
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

        if (cooldown > 0) {
            val msg = TextView(context).apply {
                text = "noch $cooldown Minute" + if (cooldown > 1) "n" else ""
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
            }
            root.addView(msg)
            // Always give the user a way out, even during cooldown — otherwise a
            // full-screen overlay with no button is an inescapable trap.
            root.addView(buildCloseButton())
            return root
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = context.getString(R.string.overlay_seconds_hint)
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
                if (seconds > BlockStore.MAX_DURATION_SECONDS) {
                    Toast.makeText(
                        context,
                        "Die maximale erlaubte Dauer ist ${BlockStore.MAX_DURATION_SECONDS} Sekunden.",
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
        // A guaranteed escape hatch: leave the blocked page without allowing it.
        root.addView(buildCloseButton())
        return root
    }

    /**
     * A "Schließen" button that removes the overlay and sends the user to the home
     * screen. It runs entirely within the overlay view, so it works even if the
     * accessibility service is no longer processing events — the user can never be
     * stuck behind the overlay. The site stays blocked: returning to it re-shows it.
     */
    private fun buildCloseButton(): View = Button(context).apply {
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
            goHome()
        }
    }

    /** Send the user to the launcher, off the blocked page. */
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
