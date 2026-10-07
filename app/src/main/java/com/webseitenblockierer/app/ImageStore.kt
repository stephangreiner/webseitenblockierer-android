package com.webseitenblockierer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * User-chosen background images, copied into the app's private storage so they
 * stay available even if the originals are deleted. One is shown at random
 * every 10 reps in the picture view.
 */
class ImageStore(private val context: Context) {

    private val dir = File(context.filesDir, "bilder").apply { mkdirs() }

    fun list(): List<File> =
        dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    fun add(uri: Uri): Boolean = try {
        val target = File(dir, "${System.currentTimeMillis()}_${System.nanoTime() % 100000}.img")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } != null
    } catch (_: Exception) {
        false
    }

    fun remove(file: File) {
        file.delete()
    }

    fun clear() {
        list().forEach { it.delete() }
    }

    fun random(): File? = list().randomOrNull()

    companion object {
        /** Decode [file] scaled down to roughly [reqWidth]×[reqHeight] to save memory. */
        fun decode(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= reqWidth &&
                bounds.outHeight / (sample * 2) >= reqHeight
            ) {
                sample *= 2
            }
            return BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
    }
}
