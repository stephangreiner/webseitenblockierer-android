package com.webseitenblockierer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Plays the user's songs while exercising, like nur10's "Eigene Audio-Datei":
 * every rep (re)starts playback, and after [IDLE_PAUSE_MS] without a rep the
 * song pauses. The position is remembered. When a song ends, a random other
 * song from the store is chosen and played on.
 */
class MusicPlayer(context: Context, private val songs: SongStore) {

    companion object {
        private const val PREFS = "nur10_musik"
        private const val KEY_SONG = "lied"
        private const val KEY_POSITION = "position"
        private const val IDLE_PAUSE_MS = 5000L
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var loadedName: String? = null
    private var lastRepAt = 0L

    /** Called whenever the current song changes (e.g. after a song ended). */
    var onSongChanged: (() -> Unit)? = null

    private val idleCheck = object : Runnable {
        override fun run() {
            if (player?.isPlaying != true) return
            if (SystemClock.elapsedRealtime() - lastRepAt >= IDLE_PAUSE_MS) {
                pause()
            } else {
                handler.postDelayed(this, 500L)
            }
        }
    }

    /** The selected song, falling back to any stored song. */
    fun currentSong() = songs.find(prefs.getString(KEY_SONG, null)) ?: songs.list().firstOrNull()

    fun select(name: String) {
        if (name == prefs.getString(KEY_SONG, null)) return
        val wasPlaying = player?.isPlaying == true
        release()
        prefs.edit().putString(KEY_SONG, name).putInt(KEY_POSITION, 0).apply()
        if (wasPlaying) start()
    }

    /** A rep was counted: keep the music running. */
    fun onRep() {
        lastRepAt = SystemClock.elapsedRealtime()
        if (player?.isPlaying != true) start()
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, 500L)
    }

    fun pause() {
        handler.removeCallbacks(idleCheck)
        player?.let {
            if (it.isPlaying) it.pause()
            prefs.edit().putInt(KEY_POSITION, it.currentPosition).apply()
        }
    }

    fun release() {
        pause()
        player?.release()
        player = null
        loadedName = null
    }

    private fun start() {
        val song = currentSong() ?: return
        try {
            if (player == null || loadedName != song.name) {
                player?.release()
                val p = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(song.path)
                    prepare()
                    setOnCompletionListener { playNext() }
                }
                player = p
                loadedName = song.name
                prefs.edit().putString(KEY_SONG, song.name).apply()
                val pos = prefs.getInt(KEY_POSITION, 0)
                if (pos in 1 until p.duration) p.seekTo(pos)
            }
            player?.start()
        } catch (_: Exception) {
            // Unplayable file — drop it from the player, counting goes on.
            player?.release()
            player = null
            loadedName = null
        }
    }

    /** Current song finished: continue with a random other song. */
    private fun playNext() {
        val next = songs.randomExcept(songs.find(loadedName))
        player?.release()
        player = null
        loadedName = null
        prefs.edit().putInt(KEY_POSITION, 0).apply()
        if (next == null) return
        prefs.edit().putString(KEY_SONG, next.name).apply()
        onSongChanged?.invoke()
        start()
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, 500L)
    }
}
