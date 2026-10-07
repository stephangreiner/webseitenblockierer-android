package com.webseitenblockierer.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * User songs for training, copied into the app's private storage. File names
 * keep the original title after a unique prefix ("<id>__<title>").
 */
class SongStore(private val context: Context) {

    private val dir = File(context.filesDir, "lieder").apply { mkdirs() }

    fun list(): List<File> =
        dir.listFiles()?.filter { it.isFile }?.sortedBy { title(it).lowercase() } ?: emptyList()

    fun find(name: String?): File? = name?.let { File(dir, it) }?.takeIf { it.isFile }

    fun add(uri: Uri): Boolean = try {
        val clean = displayName(uri).replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_")
        val target = File(dir, "${System.currentTimeMillis()}${System.nanoTime() % 1000}__$clean")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } != null
    } catch (_: Exception) {
        false
    }

    fun remove(file: File) {
        file.delete()
    }

    /** A random song, preferably a different one than [except]. */
    fun randomExcept(except: File?): File? {
        val songs = list()
        return songs.filter { it != except }.randomOrNull() ?: songs.randomOrNull()
    }

    private fun displayName(uri: Uri): String {
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return "Lied"
    }

    companion object {
        /** Title shown to the user: the original file name without prefix and extension. */
        fun title(file: File): String = file.name.substringAfter("__").substringBeforeLast('.')
    }
}
