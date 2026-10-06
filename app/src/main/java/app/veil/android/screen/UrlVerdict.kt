package app.veil.android.screen

/**
 * Pure, Android-free logic for the browser URL filter: decides whether a URL
 * read from a browser's address bar should be blocked. Unit-tested on the JVM
 * (.localcheck/test/UrlTest.kt).
 *
 * This runs on top of the DNS filter, which already refuses whole blocked
 * domains. Its job is what DNS can't see: a path or query on an allowed domain
 * (e.g. reddit.com/r/… or a search query), and admin URL rules.
 */
object UrlVerdict {

    /** The host part of a URL, lowercased and without "www.", or null. */
    fun hostOf(url: String): String? {
        var s = url.trim().lowercase()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        s = s.removePrefix("www.")
        return s.ifEmpty { null }
    }

    /** The path+query part (everything after the host), lowercased. */
    private fun pathAndQuery(url: String): String {
        var s = url.trim().lowercase()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val slash = s.indexOf('/')
        val q = s.indexOf('?')
        val start = when {
            slash >= 0 && (q < 0 || slash < q) -> slash
            q >= 0 -> q
            else -> return ""
        }
        return s.substring(start)
    }

    /**
     * A reason to block, or null to allow.
     * @param blockedParts admin substrings; a case-insensitive substring match anywhere in the URL.
     * @param keywords the word list; matched as whole words in the path/query only (so "sex" blocks
     *   /sex and ?q=sex but not the host essex.gov.uk, which the DNS keyword rule already governs).
     * @param allowHosts hosts the key-holder allowed; never blocked here.
     */
    fun blocked(
        url: String,
        blockedParts: Collection<String>,
        keywords: Collection<String>,
        allowHosts: Collection<String>,
    ): String? {
        if (url.isBlank()) return null
        val lower = url.trim().lowercase()
        if (!lower.contains('.') && !lower.startsWith("http")) return null // not a real URL (search text, etc.)
        val host = hostOf(lower)
        if (host != null && allowHosts.any { val a = it.lowercase().removePrefix("www."); host == a || host.endsWith(".$a") }) return null

        for (p in blockedParts) {
            val q = p.trim().lowercase()
            if (q.length >= 2 && lower.contains(q)) return "URL rule: $q"
        }
        val pq = pathAndQuery(lower)
        if (pq.isNotEmpty()) {
            for (k in keywords) {
                val q = k.trim().lowercase()
                if (q.length >= 3 && containsWord(pq, q)) return "keyword in page: $q"
            }
        }
        return null
    }

    /** True if [needle] appears in [text] bounded by non-letter/digit characters. */
    private fun containsWord(text: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else text[i - 1]
            val after = if (i + needle.length >= text.length) ' ' else text[i + needle.length]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = i + 1
        }
    }
}
