package com.webseitenblockierer.app

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** One note of a melody: MIDI pitch and length in quarter notes. */
data class Note(val midi: Int, val beats: Float) {
    /** Playback length; a quarter note lasts [QUARTER_SECONDS]. */
    val seconds: Float get() = (beats * QUARTER_SECONDS).coerceIn(0.12f, 2.5f)

    companion object {
        const val QUARTER_SECONDS = 0.45f
    }
}

/**
 * Melodies as text, one token per note: `<Ton><Vorzeichen?><Oktave>[/Länge][.]`
 * e.g. `E4`, `F#4/8`, `Bb3/2`, `A4/4.`. Length 1 = ganze, 2 = halbe,
 * 4 = viertel (Standard), 8 = achtel, 16 = sechzehntel; a trailing dot makes
 * it 1.5× as long. German `H` is accepted as B.
 */
object Melody {

    private val TOKEN = Regex("^([A-Ha-h])([#b]?)(-?\\d)(?:/(1|2|4|8|16))?(\\.)?$")
    private val SEMITONES = mapOf(
        'C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11, 'H' to 11
    )
    private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** Parse [text]; returns the notes, or null with [errorToken] set if a token is invalid. */
    fun parse(text: String): ParseResult {
        val notes = mutableListOf<Note>()
        for (token in text.split(Regex("[\\s,;|]+")).filter { it.isNotBlank() }) {
            val m = TOKEN.matchEntire(token) ?: return ParseResult(notes, token)
            val letter = m.groupValues[1].uppercase()[0]
            val accidental = when (m.groupValues[2]) {
                "#" -> 1
                "b" -> -1
                else -> 0
            }
            val octave = m.groupValues[3].toInt()
            val denominator = m.groupValues[4].ifEmpty { "4" }.toInt()
            var beats = 4f / denominator
            if (m.groupValues[5].isNotEmpty()) beats *= 1.5f
            val midi = 12 * (octave + 1) + SEMITONES.getValue(letter) + accidental
            notes.add(Note(midi.coerceIn(21, 108), beats))
        }
        return ParseResult(notes, null)
    }

    class ParseResult(val notes: List<Note>, val errorToken: String?)

    /** Text token for a note, as produced by the piano keyboard. */
    fun token(midi: Int, denominator: Int): String {
        val name = NAMES[midi % 12]
        val octave = midi / 12 - 1
        return if (denominator == 4) "$name$octave" else "$name$octave/$denominator"
    }

    fun frequency(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

    fun midi(name: String, octave: Int): Int =
        12 * (octave + 1) + (SEMITONES[name.uppercase(Locale.ROOT)[0]] ?: 0)

    /** C major scale from C4 upward through four octaves up to C8, then it starts over. */
    val SCALE: List<Int> = buildList {
        val steps = listOf(0, 2, 4, 5, 7, 9, 11)
        for (octave in 4..7) steps.forEach { add(12 * (octave + 1) + it) }
        add(12 * 9) // C8
    }

    // --- Built-in pieces (public domain) ----------------------------------

    val BUILT_IN: List<Pair<String, String>> = listOf(
        "Ode an die Freude" to """
            E4 E4 F4 G4 G4 F4 E4 D4 C4 C4 D4 E4 E4/4. D4/8 D4/2
            E4 E4 F4 G4 G4 F4 E4 D4 C4 C4 D4 E4 D4/4. C4/8 C4/2
            D4 D4 E4 C4 D4 E4/8 F4/8 E4 C4 D4 E4/8 F4/8 E4 D4 C4 D4 G3/2
            E4 E4 F4 G4 G4 F4 E4 D4 C4 C4 D4 E4 D4/4. C4/8 C4/2
        """,
        "Für Elise" to """
            E5/8 D#5/8 E5/8 D#5/8 E5/8 B4/8 D5/8 C5/8 A4/4
            C4/8 E4/8 A4/8 B4/4 E4/8 G#4/8 B4/8 C5/4
            E4/8 E5/8 D#5/8 E5/8 D#5/8 E5/8 B4/8 D5/8 C5/8 A4/4
            C4/8 E4/8 A4/8 B4/4 E4/8 C5/8 B4/8 A4/2
        """,
        "Eine kleine Nachtmusik" to """
            G4/4 D4/8 G4/4 D4/8 G4/8 D4/8 G4/8 B4/8 D5/2
            C5/4 A4/8 C5/4 A4/8 C5/8 A4/8 F#4/8 A4/8 D4/2
        """
    )
}

/** The user's own melodies, stored by name. */
class MelodyStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nur10_melodien", Context.MODE_PRIVATE)

    fun names(): List<String> = all().keys.sortedBy { it.lowercase() }

    fun get(name: String): String? = all()[name]

    fun save(name: String, text: String) {
        val obj = json()
        obj.put(name, text)
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    fun delete(name: String) {
        val obj = json()
        obj.remove(name)
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    private fun all(): Map<String, String> {
        val obj = json()
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }

    private fun json() = JSONObject(prefs.getString(KEY, "{}") ?: "{}")

    companion object {
        private const val KEY = "melodien"
    }
}
