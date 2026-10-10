package com.webseitenblockierer.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Create and edit own melodies, either by tapping a small piano keyboard or by
 * typing notes as text. Both edit the same text, so they can be mixed.
 */
class MelodyActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "name"
        private val LENGTHS = listOf(1 to "Ganze", 2 to "Halbe", 4 to "Viertel", 8 to "Achtel")
    }

    private lateinit var store: MelodyStore
    private val synth = SynthPlayer()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var picker: Spinner
    private lateinit var nameInput: EditText
    private lateinit var notesInput: EditText
    private lateinit var octaveLabel: TextView

    private var octave = 4
    private var length = 4
    private var editing: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MelodyStore(this)

        val column = Ui.column(this)
        column.addView(Ui.title(this, getString(R.string.melody_title)))

        picker = Spinner(this)
        column.addView(picker)

        nameInput = EditText(this).apply {
            hint = getString(R.string.melody_name_hint)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_DIM)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        column.addView(nameInput)

        // Octave and note length for the keyboard.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        controls.addView(Button(this).apply {
            text = "−"
            setOnClickListener { setOctave(octave - 1) }
        })
        octaveLabel = TextView(this).apply {
            setTextColor(Ui.TEXT)
            textSize = 15f
            setPadding(dp(8), 0, dp(8), 0)
        }
        controls.addView(octaveLabel)
        controls.addView(Button(this).apply {
            text = "+"
            setOnClickListener { setOctave(octave + 1) }
        })
        controls.addView(Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MelodyActivity,
                android.R.layout.simple_spinner_dropdown_item,
                LENGTHS.map { it.second }
            )
            setSelection(2)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(12) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    length = LENGTHS[pos].first
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        column.addView(controls)
        setOctave(4)

        column.addView(PianoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160)
            )
            onKey = { semitone ->
                val midi = 12 * (octave + 1) + semitone
                synth.playMidi(midi, Note(midi, 4f / length).seconds)
                appendToken(Melody.token(midi, length))
            }
        })

        notesInput = EditText(this).apply {
            hint = "E4 E4 F4 G4/2 …"
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            gravity = Gravity.TOP
        }
        column.addView(notesInput)
        column.addView(Ui.hint(this, getString(R.string.melody_format_hint)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = getString(R.string.melody_undo)
            setOnClickListener { removeLastToken() }
        })
        actions.addView(Button(this).apply {
            text = getString(R.string.melody_play)
            setOnClickListener { playAll() }
        })
        column.addView(actions)

        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveRow.addView(Button(this).apply {
            text = getString(R.string.melody_save)
            setOnClickListener { save() }
        })
        saveRow.addView(Button(this).apply {
            text = getString(R.string.delete)
            setOnClickListener { delete() }
        })
        column.addView(saveRow)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BACKGROUND)
            addView(column)
        })

        renderPicker(intent.getStringExtra(EXTRA_NAME))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Fill the melody picker: "Neue Melodie" plus every saved one. */
    private fun renderPicker(select: String?) {
        val names = store.names()
        val entries = listOf(getString(R.string.melody_new)) + names
        picker.onItemSelectedListener = null
        picker.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, entries
        )
        val index = names.indexOf(select)
        picker.setSelection(if (index >= 0) index + 1 else 0, false)
        load(if (index >= 0) select else null)
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                load(if (pos == 0) null else names[pos - 1])
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun load(name: String?) {
        editing = name
        nameInput.setText(name ?: "")
        notesInput.setText(name?.let { store.get(it) } ?: "")
    }

    private fun setOctave(value: Int) {
        octave = value.coerceIn(2, 7)
        octaveLabel.text = getString(R.string.melody_octave, octave)
    }

    private fun appendToken(token: String) {
        val current = notesInput.text.toString().trimEnd()
        notesInput.setText(if (current.isEmpty()) token else "$current $token")
        notesInput.setSelection(notesInput.text.length)
    }

    private fun removeLastToken() {
        val current = notesInput.text.toString().trimEnd()
        val cut = current.lastIndexOfAny(charArrayOf(' ', '\n'))
        notesInput.setText(if (cut < 0) "" else current.substring(0, cut))
        notesInput.setSelection(notesInput.text.length)
    }

    /** Parse the text; shows a message and returns null if a token is invalid. */
    private fun parsed(): List<Note>? {
        val result = Melody.parse(notesInput.text.toString())
        result.errorToken?.let {
            Toast.makeText(this, getString(R.string.melody_invalid, it), Toast.LENGTH_LONG).show()
            return null
        }
        return result.notes
    }

    private fun playAll() {
        handler.removeCallbacksAndMessages(null)
        val notes = parsed() ?: return
        var at = 0L
        for (note in notes) {
            handler.postDelayed({ synth.playMidi(note.midi, note.seconds) }, at)
            at += (note.beats * Note.QUARTER_SECONDS * 1000).toLong()
        }
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.melody_name_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val notes = parsed() ?: return
        if (notes.isEmpty()) {
            Toast.makeText(this, R.string.melody_empty, Toast.LENGTH_SHORT).show()
            return
        }
        // Renaming: drop the entry under the old name.
        editing?.let { if (it != name) store.delete(it) }
        store.save(name, notesInput.text.toString().trim())
        Toast.makeText(this, R.string.melody_saved, Toast.LENGTH_SHORT).show()
        renderPicker(name)
    }

    private fun delete() {
        val name = editing ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.song_delete_confirm, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(name)
                renderPicker(null)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
