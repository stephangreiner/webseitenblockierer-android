package com.webseitenblockierer.app

import android.os.Bundle
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** "Bilder verwalten": add and remove the pictures shown every 10 reps. */
class ImagesActivity : AppCompatActivity() {

    private lateinit var images: ImageStore
    private lateinit var info: TextView
    private lateinit var grid: GridLayout

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val added = uris.count { images.add(it) }
            if (uris.isNotEmpty() && added < uris.size) {
                Toast.makeText(this, R.string.image_add_failed, Toast.LENGTH_SHORT).show()
            }
            render()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        images = ImageStore(this)

        val column = Ui.column(this)
        column.addView(Ui.title(this, getString(R.string.images_title)))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = getString(R.string.image_add)
            setOnClickListener { pickImages.launch("image/*") }
        })
        buttons.addView(Button(this).apply {
            text = getString(R.string.image_delete_all)
            setOnClickListener {
                if (images.list().isEmpty()) return@setOnClickListener
                AlertDialog.Builder(this@ImagesActivity)
                    .setMessage(R.string.image_delete_all_confirm)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        images.clear()
                        render()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        })
        column.addView(buttons)

        info = Ui.hint(this, "")
        column.addView(info)

        grid = GridLayout(this).apply { columnCount = 4 }
        column.addView(grid)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BACKGROUND)
            addView(column)
        })
        render()
    }

    private fun render() {
        val files = images.list()
        info.text = if (files.isEmpty()) {
            getString(R.string.image_info_empty)
        } else {
            resources.getQuantityString(R.plurals.image_info_count, files.size, files.size)
        }
        grid.removeAllViews()
        val size = (resources.displayMetrics.widthPixels - dp(40)) / 4 - dp(6)
        for (file in files) {
            grid.addView(ImageView(this).apply {
                setImageBitmap(ImageStore.decode(file, size, size))
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                // Tap a thumbnail to remove just that picture.
                setOnClickListener {
                    AlertDialog.Builder(this@ImagesActivity)
                        .setMessage(R.string.image_delete_one_confirm)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            images.remove(file)
                            render()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            })
        }
    }
}
