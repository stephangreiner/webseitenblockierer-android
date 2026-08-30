package com.webseitenblockierer.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches supported browsers, extracts the currently shown URL from the address
 * bar, and shows the blocking overlay when the host is on the block list and is
 * not inside an active free window.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private lateinit var store: BlockStore
    private lateinit var overlay: OverlayManager

    private var lastHost: String? = null

    /**
     * The blocked host we have already shown the block overlay for during the
     * current visit. Once set, we do NOT re-show the block for the same host — the
     * user may have dismissed it (Back) and must be free to leave. It is cleared
     * whenever a different host is seen or the browser leaves the foreground, so a
     * *fresh* navigation back to the blocked host shows the block again.
     */
    private var blockHandledHost: String? = null

    /** Packages we treat as browsers (must match accessibility_service_config.xml). */
    private val browserPackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.opera.mini.native",
        "org.mozilla.firefox",
        "com.kiwibrowser.browser",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.yandex.browser"
    )

    /** Known address-bar view ids per browser package. */
    private val urlBarIds = listOf(
        "com.android.chrome:id/url_bar",
        "com.chrome.beta:id/url_bar",
        "com.chrome.dev:id/url_bar",
        "com.chrome.canary:id/url_bar",
        "com.brave.browser:id/url_bar",
        "com.microsoft.emmx:id/url_bar",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.opera.browser:id/url_field",
        "com.opera.mini.native:id/url_field",
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "com.kiwibrowser.browser:id/url_bar",
        "com.duckduckgo.mobile.android:id/omnibarTextInput",
        "com.vivaldi.browser:id/url_bar",
        "com.yandex.browser:id/bro_omnibar_address_title_text"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = BlockStore(this)
        overlay = OverlayManager(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // Ignore events from our own overlay/app — otherwise showing the overlay
        // would trigger events that we react to, causing a show/remove flicker.
        if (pkg == packageName) return

        // Left every supported browser (home screen, another app, launcher):
        // the browser is no longer in front, so take everything down. Clear the
        // per-visit gate so returning to a blocked page counts as a new attempt.
        if (pkg !in browserPackages) {
            overlay.removeAll()
            lastHost = null
            blockHandledHost = null
            return
        }

        val root = rootInActiveWindow ?: return

        // While the user is editing the address bar (typing, or picking an
        // autocomplete suggestion) the URL bar has input focus. That is NOT a real
        // navigation — merely seeing a blocked URL as a suggestion must not trigger
        // the block. Leave whatever is currently shown untouched and wait for an
        // actual page load.
        if (isAddressBarBeingEdited(root, pkg)) return

        val url = extractUrl(root, pkg)
        val host = url?.let { hostFromText(it) }

        // Could not read a URL this time (transient: page loading, or the overlay
        // itself is the active window). Do NOT touch the overlay — removing it here
        // and re-adding it on the next event is exactly what caused the flicker.
        if (host == null) return

        // A new host means a real navigation happened → reset the per-visit gate.
        if (host != lastHost) blockHandledHost = null
        lastHost = host

        val blocked = store.isHostBlocked(host)
        val allowed = store.isCurrentlyAllowed(host)

        when {
            blocked && allowed -> {
                // Timed free window is running: no block, show the live countdown.
                // Clear the per-visit gate so that when the window expires the block
                // is shown again for this same host.
                blockHandledHost = null
                if (overlay.isShowing) overlay.remove()
                overlay.showCountdown(host)
            }
            blocked -> {
                // Blocked and no free window. Show the block once for this visit so
                // the user is never permanently trapped: after dismissing it they can
                // navigate elsewhere, and only a fresh attempt re-shows it.
                overlay.hideCountdown()
                if (blockHandledHost != host) {
                    overlay.show(host)
                    blockHandledHost = host
                }
            }
            else -> {
                // A positively-read, non-blocked page → clear everything.
                overlay.hideCountdown()
                if (overlay.isShowing) overlay.remove()
            }
        }
    }

    override fun onInterrupt() {}

    /**
     * True while the user is actively editing the address bar (typing a URL or
     * choosing from autocomplete). In that state the URL bar view holds input focus.
     * We must not block on the text shown then — it is a draft/suggestion, not a
     * loaded page. The block is only meant to fire on a real navigation.
     */
    private fun isAddressBarBeingEdited(root: AccessibilityNodeInfo, pkg: String?): Boolean {
        val ids = buildList {
            if (pkg != null) urlBarIds.firstOrNull { it.startsWith("$pkg:") }?.let { add(it) }
            addAll(urlBarIds)
        }
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            for (node in nodes) {
                if (node.isFocused) return true
            }
        }
        return false
    }

    /** Try the known id for this package first, then any known id, then a scan. */
    private fun extractUrl(root: AccessibilityNodeInfo, pkg: String?): String? {
        if (pkg != null) {
            urlBarIds.firstOrNull { it.startsWith("$pkg:") }?.let { id ->
                textFromNodeId(root, id)?.let { return it }
            }
        }
        for (id in urlBarIds) {
            textFromNodeId(root, id)?.let { return it }
        }
        return null
    }

    private fun textFromNodeId(root: AccessibilityNodeInfo, viewId: String): String? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: return null
        for (node in nodes) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && looksLikeUrlOrHost(text)) return text
        }
        return null
    }

    private fun looksLikeUrlOrHost(text: String): Boolean {
        if (text.contains(' ')) return false
        return text.contains('.') || text.startsWith("http")
    }

    /** Derive a bare hostname from an address-bar value. */
    private fun hostFromText(text: String): String? {
        var s = text.trim().lowercase()
        if (s.isEmpty() || s.contains(' ')) return null
        s = s.substringAfter("://", s)
        s = s.substringBefore("/")
        s = s.substringBefore("?")
        s = s.substringBefore(":")
        s = s.removePrefix("www.")
        if (!s.contains('.')) return null
        return s
    }
}
