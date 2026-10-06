package app.veil.android.screen

/** One proactive-catalog rule: a section of a mixed site that is blocked or explicitly allowed. */
data class CatalogRule(val domain: String, val contains: String, val block: Boolean, val category: String)

/**
 * Pure logic for the proactive site catalog: given the shared rules, decide a URL
 * BEFORE the page loads. Mirrors backend lib/catalog.ts. Unit-tested on the JVM
 * (.localcheck/test/CatalogTest.kt). The catalog is about sites, never people.
 */
object CatalogVerdict {

    /** true = block (with category), false = explicitly allowed section, null = catalog has no opinion. */
    fun verdict(url: String, rules: List<CatalogRule>): Pair<Boolean, String>? {
        if (url.isBlank() || rules.isEmpty()) return null
        val host = UrlVerdict.hostOf(url) ?: return null
        val lower = url.trim().lowercase()
        val scheme = lower.indexOf("://")
        val afterScheme = if (scheme >= 0) lower.substring(scheme + 3) else lower
        val slash = afterScheme.indexOf('/')
        val pathAndQuery = if (slash >= 0) afterScheme.substring(slash) else ""
        var block: Pair<Boolean, String>? = null
        var allow: Pair<Boolean, String>? = null
        for (r in rules) {
            val d = r.domain.lowercase().removePrefix("www.")
            if (!(host == d || host.endsWith(".$d"))) continue
            val c = r.contains.lowercase()
            if (c.isNotEmpty() && !pathAndQuery.contains(c)) continue
            if (r.block) block = true to r.category else allow = false to r.category
        }
        // Safety-first: a blocked section wins over an allowed one if both match.
        return block ?: allow
    }
}
