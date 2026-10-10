package com.webseitenblockierer.app

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Start screen of the app — nur10 inside the blocker: count exercise reps
 * (squats, pull-ups and back extensions via the accelerometer, push-ups by
 * tapping). Every rep earns one second of credit for a blocked website.
 */
class TrainingActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val PREFS = "nur10_einstellungen"
        private const val KEY_EXERCISE = "uebung"
        private const val KEY_PICTURES = "bilderansicht"
        private const val KEY_SOUND = "tonmodus"
        private const val KEY_PIECE = "stueck"
        private const val KEY_PUSHUP_NOTE = "liegestuetzton"
        private const val KEY_RH_SMALL = "rueckenheber_klein"
        private const val KEY_RH_CURVE = "rueckenheber_kurve"

        private const val SOUND_OFF = "aus"
        private const val SOUND_FIXED = "fest"
        private const val SOUND_SCALE = "tonleiter"
        private const val SOUND_PIECE = "stueck"
        private const val SOUND_MUSIC = "lieder"
        private val SOUND_MODES =
            listOf(SOUND_OFF, SOUND_FIXED, SOUND_SCALE, SOUND_PIECE, SOUND_MUSIC)

        /** Push-up note choices: base note and the "every 10th" note above it. */
        private val PUSHUP_NOTES = listOf(
            "C" to "D", "D" to "E", "E" to "F", "F" to "G", "G" to "A", "A" to "B", "B" to "C"
        )

        // Back extensions: thresholds around a slowly tracked resting position.
        private const val RH_DELTA_NORMAL = 2.0f
        private const val RH_DELTA_SMALL = 0.7f
        private const val RH_SMOOTH_TAU = 0.06f   // s, removes sensor jitter
        private const val RH_BASELINE_TAU = 5f    // s, follows the resting position
    }

    private lateinit var credit: CreditStore
    private lateinit var stats: ExerciseStats
    private lateinit var images: ImageStore
    private lateinit var songs: SongStore
    private lateinit var music: MusicPlayer
    private lateinit var melodies: MelodyStore
    private val synth = SynthPlayer()
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())

    // Settings
    private var exercise = Exercise.KNIEBEUGEN
    private var pictureView = false
    private var sound = SOUND_FIXED
    private var piece = "b:0"
    private var pushupNote = 0
    private var rhSmall = false
    private var rhCurve = false

    // Session state
    private var active = false
    private var count = 0
    private var startedAt = 0L
    private var counter: RepCounter? = null
    private var pieceNotes: List<Note> = emptyList()
    private var smoothed = Float.NaN
    private var baseline = Float.NaN
    private var lastSampleNanos = 0L

    // Setup views
    private lateinit var setupRoot: View
    private lateinit var creditLabel: TextView
    private lateinit var startButton: LinearLayout
    private lateinit var startIcon: ImageView
    private lateinit var startLabel: TextView
    private lateinit var pieceSpinner: Spinner
    private var pieceKeys: List<String> = emptyList()

    // Active views
    private lateinit var activeRoot: FrameLayout
    private lateinit var backgroundImage: ImageView
    private lateinit var graph: SensorGraphView
    private lateinit var countText: TextView
    private lateinit var timerText: TextView
    private lateinit var activeCredit: TextView
    private lateinit var noteSpinner: Spinner
    private lateinit var rhControls: LinearLayout
    private lateinit var rhSmallButton: Button
    private lateinit var rhCurveButton: Button

    private val timerTick = object : Runnable {
        override fun run() {
            if (!active) return
            val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000
            timerText.text = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credit = CreditStore(this)
        stats = ExerciseStats(this)
        images = ImageStore(this)
        songs = SongStore(this)
        music = MusicPlayer(this, songs)
        melodies = MelodyStore(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        exercise = Exercise.fromKey(prefs.getString(KEY_EXERCISE, null))
        pictureView = prefs.getBoolean(KEY_PICTURES, false)
        sound = prefs.getString(KEY_SOUND, null)?.takeIf { it in SOUND_MODES } ?: SOUND_FIXED
        piece = prefs.getString(KEY_PIECE, "b:0") ?: "b:0"
        pushupNote = prefs.getInt(KEY_PUSHUP_NOTE, 0).coerceIn(0, PUSHUP_NOTES.size - 1)
        rhSmall = prefs.getBoolean(KEY_RH_SMALL, false)
        rhCurve = prefs.getBoolean(KEY_RH_CURVE, false)

        setupRoot = buildSetupView()
        activeRoot = buildActiveView()
        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.BACKGROUND)
            addView(setupRoot)
            addView(activeRoot)
        }
        setContentView(root)
        showSetup()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (active) stopSession() else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (active && exercise.usesSensor) registerSensor()
        if (!active) {
            // Songs or melodies may have been changed on their own screens.
            music.release()
            renderPieces()
        }
        refreshCredit()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        music.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        music.release()
        super.onDestroy()
    }

    // =====================================================================
    // Setup screen
    // =====================================================================

    private fun buildSetupView(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        // Top row: credit and the way to the website blocker.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        creditLabel = TextView(this).apply {
            setTextColor(Ui.TEXT_DIM)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        top.addView(creditLabel)
        top.addView(TextView(this).apply {
            text = getString(R.string.blocker_link)
            setTextColor(Ui.ACCENT)
            textSize = 15f
            setPadding(dp(8), dp(8), 0, dp(8))
            setOnClickListener {
                startActivity(Intent(this@TrainingActivity, MainActivity::class.java))
            }
        })
        column.addView(top, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Big start button with the exercise picture, as in nur10.
        startIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(170))
        }
        startLabel = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        startButton = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(240))
            isClickable = true
            addView(startIcon)
            addView(startLabel)
            setOnClickListener { startSession() }
        }
        column.addView(startButton)

        // Slider to switch between the four exercises, as in nur10.
        column.addView(SeekBar(this).apply {
            max = Exercise.values().size - 1
            progress = exercise.ordinal
            layoutParams = LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(20); bottomMargin = dp(12) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    exercise = Exercise.values()[value]
                    prefs.edit().putString(KEY_EXERCISE, exercise.key).apply()
                    renderExercise()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })

        // Picture dropdown: number / pictures / manage pictures.
        column.addView(dropdownRow(
            getString(R.string.view_label),
            listOf(getString(R.string.view_number), getString(R.string.view_pictures),
                getString(R.string.images_manage)),
            if (pictureView) 1 else 0,
            manageIndex = 2,
            onManage = { startActivity(Intent(this, ImagesActivity::class.java)) }
        ) { pos ->
            pictureView = pos == 1
            prefs.edit().putBoolean(KEY_PICTURES, pictureView).apply()
        })

        // Audio dropdown: off / tones / piano piece / own songs / manage songs.
        column.addView(dropdownRow(
            getString(R.string.sound_label),
            listOf(getString(R.string.sound_off), getString(R.string.sound_fixed),
                getString(R.string.sound_scale), getString(R.string.sound_piece),
                getString(R.string.sound_music), getString(R.string.songs_manage)),
            SOUND_MODES.indexOf(sound),
            manageIndex = SOUND_MODES.size,
            onManage = { startActivity(Intent(this, SongsActivity::class.java)) }
        ) { pos ->
            sound = SOUND_MODES[pos]
            prefs.edit().putString(KEY_SOUND, sound).apply()
            renderPieces()
        })

        // Piano piece dropdown, only for "Klavierstück".
        pieceSpinner = Spinner(this)
        column.addView(labeledRow(getString(R.string.piece_label), pieceSpinner))

        column.addView(Button(this).apply {
            text = getString(R.string.stats_button)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            setOnClickListener {
                startActivity(Intent(this@TrainingActivity, StatsActivity::class.java))
            }
        })

        return ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
    }

    /**
     * A labelled dropdown. Choosing [manageIndex] opens a management screen via
     * [onManage] and keeps the previous choice selected.
     */
    private fun dropdownRow(
        label: String,
        entries: List<String>,
        selected: Int,
        manageIndex: Int,
        onManage: () -> Unit,
        onSelect: (Int) -> Unit
    ): View {
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, entries)
        var current = selected.coerceIn(0, entries.size - 1)
        spinner.setSelection(current, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == manageIndex) {
                    spinner.setSelection(current, false)
                    onManage()
                } else if (pos != current) {
                    current = pos
                    onSelect(pos)
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        return labeledRow(label, spinner)
    }

    private fun labeledRow(label: String, spinner: Spinner) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(TextView(this@TrainingActivity).apply {
            text = label
            setTextColor(Ui.TEXT_DIM)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        spinner.background = Ui.rounded(Color.argb(40, 255, 255, 255), dp(8).toFloat())
        addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Fill the piece dropdown: built-in pieces, own melodies, "Melodien bearbeiten…". */
    private fun renderPieces() {
        val row = pieceSpinner.parent as View
        row.visibility = if (sound == SOUND_PIECE) View.VISIBLE else View.GONE

        val userNames = melodies.names()
        pieceKeys = Melody.BUILT_IN.indices.map { "b:$it" } + userNames.map { "u:$it" }
        val labels = Melody.BUILT_IN.map { it.first } + userNames.map { "♪ $it" } +
            getString(R.string.melody_edit)
        if (piece !in pieceKeys) piece = "b:0"

        pieceSpinner.onItemSelectedListener = null
        pieceSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        pieceSpinner.setSelection(pieceKeys.indexOf(piece), false)
        pieceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos >= pieceKeys.size) {
                    pieceSpinner.setSelection(pieceKeys.indexOf(piece), false)
                    val intent = Intent(this@TrainingActivity, MelodyActivity::class.java)
                    if (piece.startsWith("u:")) {
                        intent.putExtra(MelodyActivity.EXTRA_NAME, piece.removePrefix("u:"))
                    }
                    startActivity(intent)
                } else {
                    piece = pieceKeys[pos]
                    prefs.edit().putString(KEY_PIECE, piece).apply()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun loadPieceNotes(): List<Note> {
        val text = when {
            piece.startsWith("b:") ->
                Melody.BUILT_IN.getOrNull(piece.removePrefix("b:").toIntOrNull() ?: 0)?.second
            else -> melodies.get(piece.removePrefix("u:"))
        } ?: return emptyList()
        return Melody.parse(text).notes
    }

    private fun showSetup() {
        setupRoot.visibility = View.VISIBLE
        activeRoot.visibility = View.GONE
        renderExercise()
        renderPieces()
        refreshCredit()
    }

    private fun renderExercise() {
        startIcon.setImageResource(exercise.iconRes)
        startLabel.text = getString(R.string.start_button, exercise.label)
        startButton.background = Ui.rounded(exercise.color, dp(24).toFloat())
    }

    // =====================================================================
    // Active session
    // =====================================================================

    private fun buildActiveView(): FrameLayout {
        val frame = FrameLayout(this)

        backgroundImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(backgroundImage, matchParent())

        graph = SensorGraphView(this).apply { setBackgroundColor(Ui.BACKGROUND) }
        frame.addView(graph, matchParent())

        countText = TextView(this).apply {
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 0f, Color.WHITE)
            // Push-ups: tap anywhere (nose on the screen) to count.
            setOnClickListener { if (!exercise.usesSensor) countRep() }
        }
        frame.addView(countText, matchParent())

        timerText = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 20f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        frame.addView(timerText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))

        frame.addView(Button(this).apply {
            text = "x"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = Ui.rounded(Color.argb(170, 0, 0, 0), dp(24).toFloat())
            setOnClickListener { stopSession() }
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(12); rightMargin = dp(12)
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Back extension test switches: sensitivity and number/curve view.
        rhControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        rhSmallButton = smallButton {
            rhSmall = !rhSmall
            prefs.edit().putBoolean(KEY_RH_SMALL, rhSmall).apply()
            renderRhControls()
        }
        rhCurveButton = smallButton {
            rhCurve = !rhCurve
            prefs.edit().putBoolean(KEY_RH_CURVE, rhCurve).apply()
            renderRhControls()
        }
        rhControls.addView(rhSmallButton)
        rhControls.addView(rhCurveButton)
        bottom.addView(rhControls)

        activeCredit = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = Ui.rounded(Color.argb(170, 0, 0, 0), dp(16).toFloat())
        }
        bottom.addView(activeCredit)

        noteSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@TrainingActivity,
                android.R.layout.simple_spinner_dropdown_item,
                PUSHUP_NOTES.map { it.first }
            )
            setSelection(pushupNote)
            background = Ui.rounded(Color.argb(170, 255, 255, 255), dp(8).toFloat())
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    pushupNote = pos
                    prefs.edit().putInt(KEY_PUSHUP_NOTE, pos).apply()
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        bottom.addView(noteSpinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        frame.addView(bottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))
        return frame
    }

    private fun smallButton(onClick: () -> Unit) = Button(this).apply {
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = Ui.rounded(Color.argb(170, 0, 0, 0), dp(16).toFloat())
        setPadding(dp(14), 0, dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
        ).apply { leftMargin = dp(4); rightMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun startSession() {
        if (exercise.usesSensor && accelerometer == null) {
            Toast.makeText(this, R.string.no_sensor, Toast.LENGTH_LONG).show()
            return
        }
        active = true
        count = 0
        startedAt = SystemClock.elapsedRealtime()
        counter = RepCounter(squatStyle = exercise == Exercise.KNIEBEUGEN) { countRep() }
        pieceNotes = if (sound == SOUND_PIECE) loadPieceNotes() else emptyList()
        smoothed = Float.NaN
        baseline = Float.NaN
        lastSampleNanos = 0L
        graph.clear()

        setupRoot.visibility = View.GONE
        activeRoot.visibility = View.VISIBLE
        activeRoot.setBackgroundColor(exercise.color)
        backgroundImage.setImageDrawable(null)
        noteSpinner.visibility =
            if (!exercise.usesSensor && sound == SOUND_FIXED) View.VISIBLE else View.GONE
        renderRhControls()
        renderCount()
        refreshCredit()

        // Back extensions: the phone is held sideways, so show the session in landscape.
        if (exercise == Exercise.RUECKENHEBER) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        // Keep the screen on while training, like nur10's wake lock.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (exercise.usesSensor) registerSensor()
        handler.post(timerTick)
    }

    private fun stopSession() {
        active = false
        counter = null
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(timerTick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        backgroundImage.setImageDrawable(null)
        music.pause()
        showSetup()
    }

    /** Back-extension switches are only shown for that exercise. */
    private fun renderRhControls() {
        val rh = exercise == Exercise.RUECKENHEBER
        rhControls.visibility = if (rh) View.VISIBLE else View.GONE
        rhSmallButton.text = getString(
            if (rhSmall) R.string.rh_sensitivity_small else R.string.rh_sensitivity_normal
        )
        rhCurveButton.text = getString(if (rhCurve) R.string.rh_view_curve else R.string.rh_view_number)

        val curve = rh && rhCurve
        graph.visibility = if (curve) View.VISIBLE else View.GONE
        // In the curve view the count moves to the top so the graph stays readable.
        countText.textSize = if (curve) 40f else 120f
        countText.gravity = if (curve) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        countText.setPadding(0, if (curve) dp(8) else 0, 0, 0)
        countText.setTextColor(if (curve) Color.WHITE else Color.BLACK)
        countText.setShadowLayer(12f, 0f, 0f, if (curve) Color.BLACK else Color.WHITE)
    }

    private fun registerSensor() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val axis = exercise.axis ?: return
        val counter = counter ?: return
        if (!active) return
        val value = event.values[axis]
        val now = SystemClock.elapsedRealtime()

        if (exercise != Exercise.RUECKENHEBER) {
            counter.onValue(value, now)
            return
        }

        // Back extensions: the phone moves only a little while lying on the belly
        // and is held at an angle, so count around the measured resting position.
        val dt = if (lastSampleNanos == 0L) 0.02f
        else ((event.timestamp - lastSampleNanos) / 1e9f).coerceIn(0.001f, 0.2f)
        lastSampleNanos = event.timestamp
        if (smoothed.isNaN()) {
            smoothed = value
            baseline = value
        }
        smoothed += (value - smoothed) * (dt / (RH_SMOOTH_TAU + dt))
        baseline += (smoothed - baseline) * (dt / (RH_BASELINE_TAU + dt))

        val delta = if (rhSmall) RH_DELTA_SMALL else RH_DELTA_NORMAL
        counter.low = baseline - delta
        counter.high = baseline + delta
        counter.onValue(smoothed, now)
        if (rhCurve) graph.addSample(smoothed, baseline, counter.low, counter.high)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** One rep done: count it, store it, and earn one second of credit. */
    private fun countRep() {
        if (!active) return
        count += 1
        stats.increment(exercise)
        credit.add(1)
        if (exercise == Exercise.RUECKENHEBER) graph.markRep()

        playRepSound()
        if (pictureView && count % 10 == 0) showRandomImage()

        renderCount()
        refreshCredit()
    }

    private fun playRepSound() {
        val tenth = count % 10 == 0
        when (sound) {
            SOUND_MUSIC -> music.onRep()
            SOUND_FIXED -> {
                if (exercise.usesSensor) {
                    synth.play(if (tenth) "C" else "E", 4, 0.5f)
                } else {
                    val (base, high) = PUSHUP_NOTES[pushupNote]
                    synth.play(if (tenth) high else base, 4, 1f)
                }
            }
            SOUND_SCALE -> {
                // Every 10th rep climbs one step up the C major scale.
                if (tenth) {
                    val step = (count / 10 - 1) % Melody.SCALE.size
                    synth.playMidi(Melody.SCALE[step], 0.7f)
                } else {
                    synth.play("E", 4, 0.35f)
                }
            }
            SOUND_PIECE -> {
                // Every rep plays the next note of the piece, so the tempo of the
                // reps is the tempo of the music.
                val note = pieceNotes.getOrNull((count - 1) % pieceNotes.size.coerceAtLeast(1))
                if (note != null) synth.playMidi(note.midi, note.seconds)
            }
        }
    }

    private fun showRandomImage() {
        val file = images.random() ?: return
        val w = activeRoot.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = activeRoot.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        backgroundImage.setImageBitmap(ImageStore.decode(file, w, h))
    }

    private fun renderCount() {
        countText.text = count.toString()
    }

    private fun refreshCredit() {
        val text = getString(R.string.credit_balance, credit.balance())
        creditLabel.text = text
        activeCredit.text = text
    }
}
