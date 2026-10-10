package com.webseitenblockierer.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Small piano-like tone generator, the native counterpart of nur10's
 * audiosynth.js "Standard-Ton". Tones are rendered once and cached.
 */
class SynthPlayer {

    companion object {
        private const val SAMPLE_RATE = 44100
    }

    private val cache = HashMap<String, ShortArray>()
    private val handler = Handler(Looper.getMainLooper())

    fun play(note: String, octave: Int = 4, durationSeconds: Float = 0.5f) =
        playMidi(Melody.midi(note, octave), durationSeconds)

    fun playMidi(midi: Int, durationSeconds: Float) {
        val pcm = cache.getOrPut("$midi/$durationSeconds") {
            render(Melody.frequency(midi), durationSeconds)
        }
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            handler.postDelayed({ track.release() }, (durationSeconds * 1000).toLong() + 200L)
        } catch (_: Exception) {
            // Audio is optional — never let a playback problem break counting.
        }
    }

    private fun render(freq: Double, durationSeconds: Float): ShortArray {
        val count = (SAMPLE_RATE * durationSeconds).toInt()
        return ShortArray(count) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val attack = min(1.0, t / 0.005)
            val release = min(1.0, (count - i).toDouble() / (SAMPLE_RATE * 0.02))
            val w = 2 * PI * freq * t
            val v = sin(w) * exp(-3.0 * t) +
                0.5 * sin(2 * w) * exp(-4.0 * t) +
                0.25 * sin(3 * w) * exp(-6.0 * t)
            (v * attack * release * 0.35 * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
