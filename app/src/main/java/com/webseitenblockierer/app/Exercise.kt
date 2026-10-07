package com.webseitenblockierer.app

import android.graphics.Color

/**
 * The four exercises of nur10. Sensor exercises are counted with the
 * accelerometer on [axis] (0 = x, 1 = y, 2 = z); push-ups are counted by tapping.
 */
enum class Exercise(
    val key: String,
    val label: String,
    val color: Int,
    val iconRes: Int,
    val axis: Int?
) {
    KNIEBEUGEN("KB", "Kniebeugen", Color.rgb(224, 30, 30), R.drawable.ic_kniebeugen, 2),
    KLIMMZUEGE("KZ", "Klimmzüge", Color.rgb(166, 255, 142), R.drawable.ic_klimmzuege, 1),
    RUECKENHEBER("RH", "Rückenheber", Color.rgb(179, 255, 250), R.drawable.ic_rueckenheber, 0),
    LIEGESTUETZE("L", "Liegestütze", Color.rgb(240, 255, 105), R.drawable.ic_liegestuetze, null);

    val usesSensor: Boolean get() = axis != null

    companion object {
        fun fromKey(key: String?): Exercise = values().firstOrNull { it.key == key } ?: KNIEBEUGEN
    }
}
