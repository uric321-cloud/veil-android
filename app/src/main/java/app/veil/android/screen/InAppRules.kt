package app.veil.android.screen

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-app blocking: rules from the admin backend (backend/netlify/lib/inapp.ts)
 * that switch off parts of an app — cover an element, or leave a screen — while
 * the rest of the app keeps working.
 */
object InAppRules {

    class Match(val viewId: String?, val text: String?, val desc: String?, val selected: Boolean)
    class Rule(val app: String, val match: Match, val leave: Boolean)
    class Result(val covers: List<Rect>, val leave: Boolean)

    @Volatile private var byApp: Map<String, List<Rule>>? = null

    private fun prefs(c: Context) = c.getSharedPreferences("veil_inapp", Context.MODE_PRIVATE)

    fun hash(c: Context): String = prefs(c).getString("hash", "") ?: ""

    fun save(c: Context, rules: JSONArray, hash: String) {
        prefs(c).edit().putString("rules", rules.toString()).putString("hash", hash).apply()
        byApp = parse(rules)
    }

    fun clear(c: Context) {
        prefs(c).edit().clear().apply()
        byApp = emptyMap()
    }

    fun forApp(c: Context, pkg: String): List<Rule> {
        val m = byApp ?: (try { parse(JSONArray(prefs(c).getString("rules", "[]"))) } catch (_: Throwable) { emptyMap() }).also { byApp = it }
        return m[pkg] ?: emptyList()
    }

    fun parse(arr: JSONArray): Map<String, List<Rule>> {
        val out = HashMap<String, MutableList<Rule>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val app = o.optString("app").takeIf { it.isNotEmpty() } ?: continue
            val m = o.optJSONObject("match") ?: JSONObject()
            fun s(k: String) = m.optString(k, "").trim().takeIf { it.isNotEmpty() }
            val match = Match(s("viewId")?.lowercase(), s("text")?.lowercase(), s("desc")?.lowercase(), m.optBoolean("selected", false))
            if (match.viewId == null && match.text == null && match.desc == null) continue
            out.getOrPut(app) { ArrayList() }.add(Rule(app, match, o.optString("action") == "leave"))
        }
        return out
    }

    fun matches(m: Match, viewId: String?, text: String?, desc: String?, selected: Boolean): Boolean =
        InAppMatch.matches(m.viewId, m.text, m.desc, m.selected, viewId, text, desc, selected)

    private const val MAX_NODES = 2500

    fun evaluate(root: AccessibilityNodeInfo, rules: List<Rule>): Result {
        val covers = ArrayList<Rect>()
        var leave = false
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val n = stack.removeLast()
            visited++
            try {
                if (n.isVisibleToUser) {
                    val viewId = n.viewIdResourceName
                    val text = n.text?.toString()
                    val desc = n.contentDescription?.toString()
                    for (r in rules) {
                        if (!matches(r.match, viewId, text, desc, n.isSelected)) continue
                        if (r.leave) leave = true
                        else {
                            val b = Rect()
                            n.getBoundsInScreen(b)
                            if (b.width() > 0 && b.height() > 0) covers.add(b)
                        }
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
            } catch (_: Throwable) {
                // Stale node; skip.
            }
        }
        return Result(covers, leave)
    }
}
