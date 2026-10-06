package app.veil.android.screen

import android.content.Context
import app.veil.android.VeilLog
import app.veil.android.rules.RuleStore
import org.json.JSONArray

/**
 * The phone's copy of the proactive site catalog (downloaded from the admin
 * backend on check-in). Stores the rules and answers whether a URL hits a known
 * risky or explicitly-fine section of a mixed mainstream site.
 */
object SiteCatalog {

    private const val SEP = "\u0001"

    /** Store the catalog rules and version delivered in a sync response. */
    fun save(ctx: Context, rules: JSONArray, version: String) {
        val out = HashSet<String>()
        for (i in 0 until rules.length()) {
            val o = rules.optJSONObject(i) ?: continue
            val domain = o.optString("domain").trim().lowercase()
            val contains = o.optString("contains").trim().lowercase()
            val action = o.optString("action")
            val category = o.optString("category").take(40)
            if (domain.isEmpty()) continue
            val block = action == "block"
            out.add(listOf(domain, contains, if (block) "b" else "a", category).joinToString(SEP))
        }
        val s = RuleStore.get(ctx)
        s.catalogRules = out
        s.catalogVersion = version
        VeilLog.i("Site catalog updated (${out.size} rules, v$version)")
    }

    fun rules(ctx: Context): List<CatalogRule> =
        RuleStore.get(ctx).catalogRules.mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size < 3) return@mapNotNull null
            CatalogRule(p[0], p[1], p[2] == "b", p.getOrElse(3) { "" })
        }

    /** true = block (with category), false = explicitly allowed, null = no opinion. */
    fun verdict(ctx: Context, url: String): Pair<Boolean, String>? = CatalogVerdict.verdict(url, rules(ctx))
}
