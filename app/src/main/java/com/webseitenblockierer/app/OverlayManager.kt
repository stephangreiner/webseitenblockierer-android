package com.webseitenblockierer.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Draws / removes the full-screen blocking overlay on top of whatever browser
 * is showing a blocked site. This is the native counterpart of the extension's
 * content.js `createOverlay()`.
 */
class OverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val store = BlockStore(context)

    private var overlayView: View? = null
    private var shownForHost: String? = null

    val isShowing: Boolean get() = overlayView != null

    /** Whether the overlay is currently shown for this exact host. */
    fun isShowingFor(host: String): Boolean = overlayView != null && shownForHost == host

    fun show(host: String) {
        // Already showing for this host — nothing to do.
        if (isShowingFor(host)) return
        // Showing for a different host — rebuild.
        if (overlayView != null) remove()

        val root = buildOverlay(host)

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Not focusable by default so the browser's back gesture still works,
            // but we need focus for the EditText, so allow it when input is shown.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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

    private fun buildOverlay(host: String): View {
        val root = LinearLayout(context).apply {
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

        val cooldown = store.cooldownMinutesRemaining(host)
        if (cooldown > 0) {
            val msg = TextView(context).apply {
                text = "noch $cooldown Minute" + if (cooldown > 1) "n" else ""
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
            }
            root.addView(msg)
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
        return root
    }
}
