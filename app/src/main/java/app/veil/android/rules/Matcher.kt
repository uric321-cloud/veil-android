package app.veil.android.rules

import app.veil.android.dns.DnsMessage

/** A set of domains where an entry also matches every subdomain of it. */
class DomainSet(entries: Collection<String>) {
    private val set = HashSet<String>(entries.size * 2)

    init {
        for (e in entries) {
            val h = e.trim().lowercase().trim('.')
            if (h.isNotEmpty()) set.add(h)
        }
    }

    val size: Int get() = set.size

    /** Returns the matching entry (the host itself or a parent domain) or null. */
    fun match(host: String): String? {
        if (set.isEmpty()) return null
        var h = host
        while (true) {
            if (set.contains(h)) return h
            val dot = h.indexOf('.')
            if (dot < 0) return null
            h = h.substring(dot + 1)
            if (!h.contains('.')) return null // never match a bare TLD
        }
    }
}

sealed class Decision {
    /** Forward to the normal resolver. */
    object Allow : Decision()
    /** Allow-listed: forward to the unfiltered resolver, skip SafeSearch. */
    object AllowUnfiltered : Decision()
    /** Answer NXDOMAIN. */
    class Block(val reason: String, val rule: String) : Decision()
    /** Answer with the addresses of [canonical] (SafeSearch enforcement). */
    class Rewrite(val canonical: String) : Decision()
    /** Answer NOERROR with no records (used to strip HTTPS/SVCB records). */
    object NoData : Decision()
}

/**
 * Immutable snapshot of the rules. Rebuilt whenever the store changes, so the
 * hot path (one call per DNS query) does no locking and no I/O.
 */
class Matcher(
    private val adult: DomainSet?,
    private val bypassHosts: DomainSet?,
    private val customBlock: DomainSet,
    private val customAllow: DomainSet,
    private val keywords: List<String>,
    private val safeSearch: Boolean,
    private val youtubeStrict: Boolean,
    private val stripHttpsRecords: Boolean,
    /** Sites the admin backend's AI classified as adult or bypass (shared list). */
    private val aiBlock: DomainSet? = null
) {
    fun decide(host: String, qtype: Int): Decision {
        if (host.isEmpty() || !host.contains('.')) return Decision.Allow

        customAllow.match(host)?.let { return Decision.AllowUnfiltered }

        bypassHosts?.match(host)?.let { return Decision.Block("Encrypted-DNS bypass", it) }
        customBlock.match(host)?.let { return Decision.Block("Your block list", it) }
        for (k in keywords) if (host.contains(k)) return Decision.Block("Keyword “$k”", k)
        adult?.match(host)?.let { return Decision.Block("Adult content list", it) }
        aiBlock?.match(host)?.let { return Decision.Block("AI-classified site", it) }

        if (safeSearch) {
            SafeSearch.canonicalFor(host, youtubeStrict)?.let { return Decision.Rewrite(it) }
        }
        if (stripHttpsRecords && qtype == DnsMessage.TYPE_HTTPS) return Decision.NoData
        return Decision.Allow
    }

    companion object {
        fun build(store: RuleStore, lists: ListSource): Matcher {
            return Matcher(
                adult = if (store.adultListEnabled) lists.adultSet() else null,
                bypassHosts = if (store.bypassProtectionEnabled) lists.bypassSet() else null,
                customBlock = DomainSet(store.customBlock),
                customAllow = DomainSet(store.customAllow + store.liveTempAllows().keys),
                keywords = if (store.keywordsEnabled) store.keywords.map { it.lowercase() } else emptyList(),
                safeSearch = store.safeSearchEnabled,
                youtubeStrict = store.youtubeStrict,
                stripHttpsRecords = store.bypassProtectionEnabled,
                aiBlock = if (store.adultListEnabled) lists.aiBlockSet() else null
            )
        }
    }
}

/** Search engines whose "safe" variant can be enforced purely through DNS. */
object SafeSearch {
    private val googleHost = Regex("^(www\\.)?google\\.[a-z]{2,3}(\\.[a-z]{2})?$")
    private val youtubeHosts = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtubei.googleapis.com",
        "youtube.googleapis.com", "www.youtube-nocookie.com"
    )
    private val bingHosts = setOf("bing.com", "www.bing.com")
    private val ddgHosts = setOf(
        "duckduckgo.com", "www.duckduckgo.com", "start.duckduckgo.com",
        "html.duckduckgo.com", "lite.duckduckgo.com"
    )

    fun canonicalFor(host: String, youtubeStrict: Boolean): String? = when {
        host in youtubeHosts -> if (youtubeStrict) "restrict.youtube.com" else "restrictmoderate.youtube.com"
        host in bingHosts -> "strict.bing.com"
        host in ddgHosts -> "safe.duckduckgo.com"
        googleHost.matches(host) -> "forcesafesearch.google.com"
        else -> null
    }
}
