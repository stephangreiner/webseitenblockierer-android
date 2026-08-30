package com.webseitenblockierer.app

import android.content.Context
import org.json.JSONObject

/**
 * Persistent state for the blocker, backed by SharedPreferences.
 *
 * Mirrors the logic of the original Chrome extension:
 *  - blockedSites:       the list of blocked hostnames
 *  - suspendTimestamps:  when a site was last "allowed" (start of the 2h cooldown)
 *  - allowUntil:         until when a site is currently allowed (the active free window)
 */
class BlockStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "webseitenblockierer"
        private const val KEY_SITES = "blockedSites"
        private const val KEY_SUSPEND = "suspendTimestamps"
        private const val KEY_ALLOW = "allowUntil"

        /** 2 hours cooldown between allowances, as in the extension. */
        const val SUSPENSION_INTERVAL_MS = 2L * 60L * 60L * 1000L

        /** Maximum allowed free duration: 3600 seconds (1 hour). */
        const val MAX_DURATION_SECONDS = 3600
    }

    // --- Blocked sites ---------------------------------------------------

    fun getBlockedSites(): List<String> {
        val raw = prefs.getString(KEY_SITES, "[]") ?: "[]"
        return org.json.JSONArray(raw).let { arr ->
            (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotEmpty() }
        }
    }

    fun addBlockedSite(site: String): Boolean {
        val normalized = normalizeHost(site)
        if (normalized.isEmpty()) return false
        val sites = getBlockedSites().toMutableList()
        if (sites.contains(normalized)) return false
        sites.add(normalized)
        saveSites(sites)
        return true
    }

    fun removeBlockedSite(site: String) {
        val sites = getBlockedSites().toMutableList()
        sites.remove(site)
        saveSites(sites)
    }

    private fun saveSites(sites: List<String>) {
        val arr = org.json.JSONArray()
        sites.forEach { arr.put(it) }
        prefs.edit().putString(KEY_SITES, arr.toString()).apply()
    }

    /** A hostname is blocked if it equals a blocked site or is a subdomain of one. */
    fun isHostBlocked(hostname: String): Boolean {
        val host = hostname.lowercase().removePrefix("www.")
        return getBlockedSites().any { site ->
            val s = site.lowercase().removePrefix("www.")
            host == s || host.endsWith(".$s")
        }
    }

    // --- Allow window ----------------------------------------------------

    private fun readMap(key: String): JSONObject =
        JSONObject(prefs.getString(key, "{}") ?: "{}")

    private fun writeMap(key: String, obj: JSONObject) =
        prefs.edit().putString(key, obj.toString()).apply()

    /** True while the host is inside an active free window. */
    fun isCurrentlyAllowed(hostname: String): Boolean {
        val until = readMap(KEY_ALLOW).optLong(hostname, 0L)
        return System.currentTimeMillis() < until
    }

    /**
     * Epoch millis until which [hostname] is currently allowed, or 0 if there is
     * no (active or past) free window. Used to drive the live countdown.
     */
    fun allowedUntil(hostname: String): Long =
        readMap(KEY_ALLOW).optLong(hostname, 0L)

    /** Seconds remaining in the active free window for [hostname], or 0 if none. */
    fun allowSecondsRemaining(hostname: String): Int {
        val remaining = allowedUntil(hostname) - System.currentTimeMillis()
        if (remaining <= 0L) return 0
        return Math.ceil(remaining / 1000.0).toInt()
    }

    /** Minutes remaining in the 2h cooldown, or 0 if the site may be allowed now. */
    fun cooldownMinutesRemaining(hostname: String): Int {
        val last = readMap(KEY_SUSPEND).optLong(hostname, 0L)
        val elapsed = System.currentTimeMillis() - last
        if (elapsed >= SUSPENSION_INTERVAL_MS) return 0
        return Math.ceil((SUSPENSION_INTERVAL_MS - elapsed) / 60000.0).toInt()
    }

    /**
     * Start a free window of [seconds] for [hostname] and begin the 2h cooldown.
     * Returns the epoch millis at which the window ends.
     */
    fun allowFor(hostname: String, seconds: Int): Long {
        val now = System.currentTimeMillis()
        val until = now + seconds * 1000L

        val allow = readMap(KEY_ALLOW)
        allow.put(hostname, until)
        writeMap(KEY_ALLOW, allow)

        val suspend = readMap(KEY_SUSPEND)
        suspend.put(hostname, now)
        writeMap(KEY_SUSPEND, suspend)

        return until
    }

    /** Strip scheme/path and leading www., leaving a bare hostname. */
    private fun normalizeHost(input: String): String {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return ""
        s = s.substringAfter("://", s)
        s = s.substringBefore("/")
        s = s.substringBefore("?")
        s = s.substringBefore(":")
        s = s.removePrefix("www.")
        return s
    }
}
