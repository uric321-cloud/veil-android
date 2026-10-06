package app.veil.android.screen

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the current URL from a browser's address bar through the accessibility
 * tree. Only browsers whose address-bar view id we know are "supported"; for
 * the rest the URL can't be read, so the admin can choose to block them instead
 * (there'd be no way to filter inside them).
 */
object BrowserUrlReader {

    /** package -> address-bar view-id resource names to try, in order. */
    private val URL_BARS: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
        "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
        "com.chrome.dev" to listOf("com.chrome.dev:id/url_bar"),
        "org.chromium.chrome" to listOf("org.chromium.chrome:id/url_bar"),
        "com.sec.android.app.sbrowser" to listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text", "com.sec.android.app.sbrowser:id/sbrowser_url_bar"),
        "org.mozilla.firefox" to listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view", "org.mozilla.firefox:id/url_bar_title"),
        "org.mozilla.focus" to listOf("org.mozilla.focus:id/mozac_browser_toolbar_url_view", "org.mozilla.focus:id/display_url"),
        "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
        "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
        "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
        "com.opera.mini.native" to listOf("com.opera.mini.native:id/url_field"),
        "com.duckduckgo.mobile.android" to listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"),
        "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar"),
        "com.kiwibrowser.browser" to listOf("com.kiwibrowser.browser:id/url_bar"),
    )

    /** Package names of apps that are browsers but whose address bar we can't read. */
    private val UNSUPPORTED_BROWSERS = setOf(
        "com.UCMobile.intl", "com.uc.browser.en", "mark.via.gp", "acr.browser.lightning",
        "com.phoenix.browser", "com.yandex.browser", "idm.internet.download.manager.plus",
    )

    fun isSupportedBrowser(pkg: String?): Boolean = pkg != null && URL_BARS.containsKey(pkg)

    fun isKnownUnsupportedBrowser(pkg: String?): Boolean = pkg != null && pkg in UNSUPPORTED_BROWSERS

    /** The URL currently shown in [pkg]'s address bar, or null if it can't be read. */
    fun readUrl(root: AccessibilityNodeInfo?, pkg: String?): String? {
        root ?: return null
        val ids = URL_BARS[pkg] ?: return null
        for (id in ids) {
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Throwable) { null } ?: continue
            for (n in nodes) {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && t != "Search or type URL" && !t.contains(' ')) return t
            }
        }
        return null
    }
}
