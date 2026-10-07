package com.webseitenblockierer.app

/**
 * Port of nur10's accelerometer rep detection.
 *
 * The sensor value is sorted into three zones (low < [LOW], high > [HIGH], mid
 * in between). Only a fresh entry into low/high — after passing through mid —
 * is an edge, and edges closer than [DEBOUNCE_MS] are ignored.
 *  - Squats: low arms the counter, the next high (standing up) counts the rep.
 *  - Pull-ups / back extensions: high arms the counter, the next low counts.
 */
class RepCounter(
    private val squatStyle: Boolean,
    private val onRep: () -> Unit
) {
    private enum class Zone { LOW, MID, HIGH }

    companion object {
        const val LOW = 8f
        const val HIGH = 12f
        const val DEBOUNCE_MS = 250L
    }

    private var zone = Zone.MID
    private var needsMidCrossing = false
    private var armed = false
    private var lastEdgeAt = 0L

    fun onValue(value: Float, now: Long) {
        val next = when {
            value < LOW -> Zone.LOW
            value > HIGH -> Zone.HIGH
            else -> Zone.MID
        }
        if (next == zone) return
        zone = next

        if (next == Zone.MID) {
            needsMidCrossing = false
            return
        }
        if (needsMidCrossing) return

        if (next == Zone.HIGH) high(now) else low(now)
        needsMidCrossing = true
    }

    private fun high(now: Long) {
        if (now - lastEdgeAt <= DEBOUNCE_MS) return
        lastEdgeAt = now
        if (squatStyle) {
            if (armed) {
                armed = false
                onRep()
            }
        } else {
            armed = true
        }
    }

    private fun low(now: Long) {
        if (now - lastEdgeAt <= DEBOUNCE_MS) return
        lastEdgeAt = now
        if (squatStyle) {
            armed = true
        } else if (armed) {
            armed = false
            onRep()
        }
    }
}
