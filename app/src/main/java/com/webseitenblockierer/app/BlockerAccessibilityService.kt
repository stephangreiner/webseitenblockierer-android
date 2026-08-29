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
        val root = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString() ?: root.packageName?.toString()

        // If the foreground app is our own overlay/app, ignore.
        if (pkg == packageName) return

        val url = extractUrl(root, pkg)
        val host = url?.let { hostFromText(it) }

        if (host == null) {
            // Not on a recognisable page (e.g. new tab / settings): drop the overlay.
            if (overlay.isShowing) overlay.remove()
            lastHost = null
            return
        }

        lastHost = host

        val blocked = store.isHostBlocked(host)
        val allowed = store.isCurrentlyAllowed(host)

        if (blocked && !allowed) {
            overlay.show(host)
        } else if (overlay.isShowing) {
            overlay.remove()
        }
    }

    override fun onInterrupt() {}

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
