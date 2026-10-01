package app.veil.android.rules

import android.content.Context
import app.veil.android.VeilLog
import app.veil.android.screen.Severity
import app.veil.android.screen.TextAction
import app.veil.android.screen.TextRuleEngine
import app.veil.android.screen.Tiers
import org.json.JSONArray

/**
 * Loads the built-in word/phrase lists from assets, merges the key-holder's own
 * words from RuleStore, and compiles a ready-to-run TextRuleEngine for the
 * current tier and settings. The compiled index is cached; call engine() again
 * after a settings change to pick up edits.
 */
class TextRules(private val context: Context) {

    private val termIndex = HashMap<String, Pair<String, Severity>>()
    private val phrases = ArrayList<Triple<String, String, Severity>>()
    @Volatile private var loaded = false

    val termCount: Int get() { ensureLoaded(); return termIndex.size }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val text = context.assets.open("text/rules.json").bufferedReader().use { it.readText() }
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val category = o.getString("category")
                    val severity = parseSeverity(o.getString("severity"))
                    o.optJSONArray("terms")?.let { t ->
                        for (j in 0 until t.length()) addTerm(t.getString(j), category, severity)
                    }
                    o.optJSONArray("phrases")?.let { p ->
                        for (j in 0 until p.length()) {
                            val ph = p.getString(j).lowercase().trim()
                            if (ph.isNotEmpty()) phrases.add(Triple(ph, category, severity))
                        }
                    }
                }
                VeilLog.i("Text rules loaded: ${termIndex.size} terms, ${phrases.size} phrases")
            } catch (t: Throwable) {
                VeilLog.e("Text rules load failed", t)
            }
            loaded = true
        }
    }

    private fun addTerm(raw: String, category: String, severity: Severity) {
        val norm = TextRuleEngine.normalize(raw)
        if (norm.isEmpty()) return
        termIndex[norm] = category to severity
        // A collapsed alias lets the optional de-obfuscation pass match "fuuuck" etc.
        val alias = TextRuleEngine.deob(norm)
        if (alias.isNotEmpty() && alias !in termIndex) termIndex[alias] = category to severity
    }

    private fun parseSeverity(s: String): Severity = when (s.lowercase()) {
        "mild" -> Severity.MILD
        "explicit" -> Severity.EXPLICIT
        else -> Severity.STRONG
    }

    /** Builds the engine for the current tier and settings in the store. */
    fun engine(store: RuleStore): TextRuleEngine {
        ensureLoaded()

        // Start from the built-in index, then layer the user's own words on top.
        val idx = HashMap(termIndex)
        for (w in store.textBlockWords) {
            val norm = TextRuleEngine.normalize(w)
            if (norm.isNotEmpty()) idx[norm] = "custom" to Severity.EXPLICIT
        }
        val allow = store.textAllowWords.mapNotNull {
            TextRuleEngine.normalize(it).takeIf { n -> n.isNotEmpty() }
        }.toSet()

        val tier = store.textTier
        val actions = if (tier == Tiers.CUSTOM) store.customTierActions() else Tiers.actions(tier)
        val categories = (if (tier == Tiers.CUSTOM) Tiers.ALL_CATEGORIES else Tiers.categories(tier)) + "custom"

        return TextRuleEngine(
            termIndex = idx,
            phrases = phrases,
            tierActions = actions,
            enabledCategories = categories,
            deobfuscate = store.textDeobfuscate,
            logOnly = store.textWarnLogOnly,
            allow = allow
        )
    }

    companion object {
        fun actionFromName(name: String): TextAction = when (name.lowercase()) {
            "strike" -> TextAction.STRIKE
            "bar" -> TextAction.BAR
            "frost" -> TextAction.FROST
            "ignore" -> TextAction.IGNORE
            else -> TextAction.BAR
        }
    }
}
