package com.webseitenblockierer.app

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** "Lieder verwalten": upload, choose and delete the songs played while training. */
class SongsActivity : AppCompatActivity() {

    private lateinit var songs: SongStore
    private lateinit var music: MusicPlayer
    private lateinit var list: LinearLayout

    private val pickSongs =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val added = uris.count { songs.add(it) }
            if (uris.isNotEmpty() && added < uris.size) {
                Toast.makeText(this, R.string.song_add_failed, Toast.LENGTH_SHORT).show()
            }
            render()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        songs = SongStore(this)
        music = MusicPlayer(this, songs)

        val column = Ui.column(this)
        column.addView(Ui.title(this, getString(R.string.songs_title)))
        column.addView(Button(this).apply {
            text = getString(R.string.song_add)
            setOnClickListener { pickSongs.launch("audio/*") }
        })
        column.addView(Ui.hint(this, getString(R.string.song_hint)))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BACKGROUND)
            addView(column)
        })
        render()
    }

    override fun onDestroy() {
        music.release()
        super.onDestroy()
    }

    private fun render() {
        list.removeAllViews()
        val files = songs.list()
        if (files.isEmpty()) {
            list.addView(Ui.hint(this, getString(R.string.song_info_empty)))
            return
        }
        val current = music.currentSong()
        for (file in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                val selected = file == current
                text = (if (selected) "▶ " else "") + SongStore.title(file)
                setTextColor(if (selected) Ui.ACCENT else Ui.TEXT)
                textSize = 15f
                setPadding(0, dp(10), dp(8), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                // Tap a title to make it the current song.
                setOnClickListener {
                    music.select(file.name)
                    render()
                }
            })
            row.addView(Button(this).apply {
                text = "x"
                setOnClickListener {
                    AlertDialog.Builder(this@SongsActivity)
                        .setMessage(getString(R.string.song_delete_confirm, SongStore.title(file)))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            if (file == music.currentSong()) music.release()
                            songs.remove(file)
                            render()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            })
            list.addView(row)
        }
    }
}
