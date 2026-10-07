package com.webseitenblockierer.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * nur10 inside the blocker: count exercise reps (squats, pull-ups and back
 * extensions via the accelerometer, push-ups by tapping). Every rep earns one
 * second of credit that can be spent on a blocked website.
 */
class TrainingActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val PREFS = "nur10_einstellungen"
        private const val KEY_EXERCISE = "uebung"
        private const val KEY_PICTURES = "bilderansicht"
        private const val KEY_SOUND = "ton"
        private const val KEY_PUSHUP_NOTE = "liegestuetzton"

        private val BACKGROUND = Color.rgb(15, 18, 28)
        private val SURFACE = Color.rgb(24, 29, 42)
        private val TEXT = Color.argb(242, 255, 255, 255)
        private val TEXT_DIM = Color.argb(184, 223, 228, 242)

        /** Push-up note choices: base note and the "every 10th" note above it. */
        private val PUSHUP_NOTES = listOf(
            "C" to "D", "D" to "E", "E" to "F", "F" to "G", "G" to "A", "A" to "B", "B" to "C"
        )
    }

    private lateinit var credit: CreditStore
    private lateinit var stats: ExerciseStats
    private lateinit var images: ImageStore
    private val synth = SynthPlayer()
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())

    // Settings
    private var exercise = Exercise.KNIEBEUGEN
    private var pictureView = false
    private var sound = true
    private var pushupNote = 0

    // Session state
    private var active = false
    private var count = 0
    private var startedAt = 0L
    private var counter: RepCounter? = null

    // Setup views
    private lateinit var setupRoot: View
    private lateinit var creditLabel: TextView
    private lateinit var startButton: Button
    private val exerciseButtons = mutableMapOf<Exercise, ImageView>()
    private lateinit var imageSection: LinearLayout
    private lateinit var imageInfo: TextView
    private lateinit var thumbRow: LinearLayout

    // Active views
    private lateinit var activeRoot: FrameLayout
    private lateinit var backgroundImage: ImageView
    private lateinit var countText: TextView
    private lateinit var timerText: TextView
    private lateinit var activeCredit: TextView
    private lateinit var noteSpinner: Spinner

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val added = uris.count { images.add(it) }
            if (uris.isNotEmpty() && added < uris.size) {
                Toast.makeText(this, R.string.image_add_failed, Toast.LENGTH_SHORT).show()
            }
            renderImages()
        }

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
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        exercise = Exercise.fromKey(prefs.getString(KEY_EXERCISE, null))
        pictureView = prefs.getBoolean(KEY_PICTURES, false)
        sound = prefs.getBoolean(KEY_SOUND, true)
        pushupNote = prefs.getInt(KEY_PUSHUP_NOTE, 0).coerceIn(0, PUSHUP_NOTES.size - 1)

        setupRoot = buildSetupView()
        activeRoot = buildActiveView()
        val root = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
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
        refreshCredit()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // =====================================================================
    // Setup screen
    // =====================================================================

    private fun buildSetupView(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        column.addView(TextView(this).apply {
            text = getString(R.string.training_title)
            setTextColor(TEXT)
            textSize = 22f
            gravity = Gravity.CENTER
        })

        creditLabel = TextView(this).apply {
            setTextColor(TEXT_DIM)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        }
        column.addView(creditLabel)

        startButton = Button(this).apply {
            textSize = 22f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220))
            setOnClickListener { startSession() }
        }
        column.addView(startButton)

        // Exercise picker (replaces nur10's slider).
        val exerciseRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        for (ex in Exercise.values()) {
            val icon = ImageView(this).apply {
                setImageResource(ex.iconRes)
                contentDescription = ex.label
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                    leftMargin = dp(4); rightMargin = dp(4)
                }
                setOnClickListener {
                    exercise = ex
                    prefs.edit().putString(KEY_EXERCISE, ex.key).apply()
                    renderExercise()
                }
            }
            exerciseButtons[ex] = icon
            exerciseRow.addView(icon)
        }
        column.addView(exerciseRow)

        // View: number only or with pictures.
        column.addView(sectionLabel(getString(R.string.view_label)))
        column.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val number = radio(getString(R.string.view_number))
            val pictures = radio(getString(R.string.view_pictures))
            addView(number)
            addView(pictures)
            check(if (pictureView) pictures.id else number.id)
            setOnCheckedChangeListener { _, id ->
                pictureView = id == pictures.id
                prefs.edit().putBoolean(KEY_PICTURES, pictureView).apply()
                renderImages()
            }
        })

        imageSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val imageButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        imageButtons.addView(Button(this).apply {
            text = getString(R.string.image_add)
            setOnClickListener { pickImages.launch("image/*") }
        })
        imageButtons.addView(Button(this).apply {
            text = getString(R.string.image_delete_all)
            setOnClickListener {
                if (images.list().isEmpty()) return@setOnClickListener
                AlertDialog.Builder(this@TrainingActivity)
                    .setMessage(R.string.image_delete_all_confirm)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        images.clear()
                        renderImages()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        })
        imageSection.addView(imageButtons)
        imageInfo = TextView(this).apply {
            setTextColor(TEXT_DIM)
            textSize = 13f
        }
        imageSection.addView(imageInfo)
        thumbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        imageSection.addView(HorizontalScrollView(this).apply { addView(thumbRow) })
        column.addView(imageSection)

        // Sound.
        column.addView(sectionLabel(getString(R.string.sound_label)))
        column.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val off = radio(getString(R.string.sound_off))
            val on = radio(getString(R.string.sound_on))
            addView(off)
            addView(on)
            check(if (sound) on.id else off.id)
            setOnCheckedChangeListener { _, id ->
                sound = id == on.id
                prefs.edit().putBoolean(KEY_SOUND, sound).apply()
            }
        })

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

    private fun showSetup() {
        setupRoot.visibility = View.VISIBLE
        activeRoot.visibility = View.GONE
        renderExercise()
        renderImages()
        refreshCredit()
    }

    private fun renderExercise() {
        startButton.text = getString(R.string.start_button, exercise.label)
        startButton.background = rounded(exercise.color, dp(24).toFloat())
        exerciseButtons.forEach { (ex, view) ->
            view.background = rounded(
                if (ex == exercise) ex.color else SURFACE, dp(12).toFloat()
            )
        }
    }

    private fun renderImages() {
        imageSection.visibility = if (pictureView) View.VISIBLE else View.GONE
        val files = images.list()
        imageInfo.text = if (files.isEmpty()) {
            getString(R.string.image_info_empty)
        } else {
            resources.getQuantityString(R.plurals.image_info_count, files.size, files.size)
        }
        thumbRow.removeAllViews()
        for (file in files) {
            thumbRow.addView(ImageView(this).apply {
                setImageBitmap(ImageStore.decode(file, dp(72), dp(72)))
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                    rightMargin = dp(6); topMargin = dp(6)
                }
                // Tap a thumbnail to remove just that picture.
                setOnClickListener {
                    AlertDialog.Builder(this@TrainingActivity)
                        .setMessage(R.string.image_delete_one_confirm)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            images.remove(file)
                            renderImages()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            })
        }
    }

    // =====================================================================
    // Active session
    // =====================================================================

    private fun buildActiveView(): FrameLayout {
        val frame = FrameLayout(this)

        backgroundImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(backgroundImage)

        countText = TextView(this).apply {
            textSize = 120f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 0f, Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Push-ups: tap anywhere (nose on the screen) to count.
            setOnClickListener { if (!exercise.usesSensor) countRep() }
        }
        frame.addView(countText)

        timerText = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 20f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        }
        frame.addView(timerText)

        frame.addView(Button(this).apply {
            text = "x"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(170, 0, 0, 0), dp(24).toFloat())
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END)
                .apply { topMargin = dp(12); rightMargin = dp(12) }
            setOnClickListener { stopSession() }
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        activeCredit = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(Color.argb(170, 0, 0, 0), dp(16).toFloat())
        }
        bottom.addView(activeCredit)

        noteSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@TrainingActivity,
                android.R.layout.simple_spinner_dropdown_item,
                PUSHUP_NOTES.map { it.first }
            )
            setSelection(pushupNote)
            background = rounded(Color.argb(170, 255, 255, 255), dp(8).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    pushupNote = pos
                    prefs.edit().putInt(KEY_PUSHUP_NOTE, pos).apply()
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        bottom.addView(noteSpinner)
        frame.addView(bottom)

        return frame
    }

    private fun startSession() {
        if (exercise.usesSensor && accelerometer == null) {
            Toast.makeText(this, R.string.no_sensor, Toast.LENGTH_LONG).show()
            return
        }
        active = true
        count = 0
        startedAt = SystemClock.elapsedRealtime()
        counter = RepCounter(squatStyle = exercise == Exercise.KNIEBEUGEN) { countRep() }

        setupRoot.visibility = View.GONE
        activeRoot.visibility = View.VISIBLE
        activeRoot.setBackgroundColor(exercise.color)
        backgroundImage.setImageDrawable(null)
        noteSpinner.visibility = if (exercise.usesSensor) View.GONE else View.VISIBLE
        renderCount()
        refreshCredit()

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
        backgroundImage.setImageDrawable(null)
        showSetup()
    }

    private fun registerSensor() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val axis = exercise.axis ?: return
        if (!active) return
        counter?.onValue(event.values[axis], SystemClock.elapsedRealtime())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** One rep done: count it, store it, and earn one second of credit. */
    private fun countRep() {
        if (!active) return
        count += 1
        stats.increment(exercise)
        credit.add(1)

        if (sound) {
            val tenth = count % 10 == 0
            if (exercise.usesSensor) {
                synth.play(if (tenth) "C" else "E", 4, 0.5f)
            } else {
                val (base, high) = PUSHUP_NOTES[pushupNote]
                synth.play(if (tenth) high else base, 4, 1f)
            }
        }
        if (pictureView && count % 10 == 0) showRandomImage()

        renderCount()
        refreshCredit()
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

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun sectionLabel(label: String) = TextView(this).apply {
        text = label
        setTextColor(TEXT_DIM)
        textSize = 13f
        isAllCaps = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
    }

    private fun radio(label: String) = RadioButton(this).apply {
        id = View.generateViewId()
        text = label
        setTextColor(TEXT)
        setPadding(0, 0, dp(16), 0)
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
