package app.veil.android.remote

import android.content.Context
import app.veil.android.admin.DeviceOwner
import app.veil.android.rules.RuleStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Applies the admin's settings (backend/netlify/lib/config.ts, same field
 * names) to this phone. Every value is optional: a missing or malformed field
 * leaves the current setting alone. Writes only what changed, so the VPN and
 * screen filter rebuild only when they need to.
 */
object RemoteConfig {

    fun apply(context: Context, c: JSONObject) {
        val s = RuleStore.get(context)

        bool(c, "adultList") { if (s.adultListEnabled != it) s.adultListEnabled = it }
        bool(c, "keywordsEnabled") { if (s.keywordsEnabled != it) s.keywordsEnabled = it }
        strings(c, "keywords") { if (s.keywords != it) s.keywords = it }
        bool(c, "safeSearch") { if (s.safeSearchEnabled != it) s.safeSearchEnabled = it }
        bool(c, "youtubeStrict") { if (s.youtubeStrict != it) s.youtubeStrict = it }
        bool(c, "bypassProtection") { if (s.bypassProtectionEnabled != it) s.bypassProtectionEnabled = it }
        bool(c, "upstreamFamily") { if (s.upstreamFamilyFilter != it) s.upstreamFamilyFilter = it }
        bool(c, "notifyOnBlock") { if (s.notifyOnBlock != it) s.notifyOnBlock = it }
        strings(c, "customBlock") { v -> val n = v.mapNotNull { RuleStore.normalizeHost(it) }.toSet(); if (s.customBlock != n) s.customBlock = n }
        strings(c, "customAllow") { v -> val n = v.mapNotNull { RuleStore.normalizeHost(it) }.toSet(); if (s.customAllow != n) s.customAllow = n }
        c.optJSONArray("tempAllow")?.let { arr ->
            val now = System.currentTimeMillis()
            val m = HashMap<String, Long>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val h = RuleStore.normalizeHost(o.optString("host")) ?: continue
                val until = o.optLong("until", 0)
                if (until > now) m[h] = maxOf(m[h] ?: 0, until)
            }
            if (s.tempAllows != m) s.tempAllows = m
        }
        bool(c, "aiClassification") { if (s.aiClassification != it) s.aiClassification = it }
        if (c.has("webMode")) { val on = c.optString("webMode") == "allowlist"; if (s.webAllowlistMode != on) s.webAllowlistMode = on }
        bool(c, "aiAutoAllowSafe") { if (s.aiAutoAllowSafe != it) s.aiAutoAllowSafe = it }

        c.optJSONObject("screen")?.let { sc ->
            bool(sc, "enabled") { if (s.textEnabled != it) s.textEnabled = it }
            str(sc, "tier", setOf("young_child", "child", "teen", "adult", "custom")) { if (s.textTier != it) s.textTier = it }
            bool(sc, "logOnly") { if (s.textWarnLogOnly != it) s.textWarnLogOnly = it }
            bool(sc, "deobfuscate") { if (s.textDeobfuscate != it) s.textDeobfuscate = it }
            val actions = setOf("ignore", "strike", "bar", "frost")
            str(sc, "customMild", actions) { if (s.customMild != it) s.customMild = it }
            str(sc, "customStrong", actions) { if (s.customStrong != it) s.customStrong = it }
            str(sc, "customExplicit", actions) { if (s.customExplicit != it) s.customExplicit = it }
            bool(sc, "images") { if (s.imageFilter != it) s.imageFilter = it }
            str(sc, "imageStrictness", setOf("low", "medium", "high")) { if (s.imageStrictness != it) s.imageStrictness = it }
            strings(sc, "blockWords") { if (s.textBlockWords != it) s.textBlockWords = it }
            strings(sc, "allowWords") { if (s.textAllowWords != it) s.textAllowWords = it }
            strings(sc, "safeListApps", lowercase = false) { v -> val n = v + context.packageName; if (s.safeListApps != n) s.safeListApps = n }
        }

        c.optJSONObject("lockdown")?.let { DeviceOwner.applyPolicy(context, DeviceOwner.Policy.from(it)) }
        c.optJSONObject("apps")?.let { app.veil.android.apps.AppControl.setPolicy(context, app.veil.android.apps.AppControl.Policy.from(it)) }
    }

    private inline fun bool(o: JSONObject, key: String, set: (Boolean) -> Unit) {
        if (o.has(key)) (o.opt(key) as? Boolean)?.let(set)
    }

    private inline fun str(o: JSONObject, key: String, allowed: Set<String>, set: (String) -> Unit) {
        val v = o.optString(key, "")
        if (v in allowed) set(v)
    }

    /** Package names are case-sensitive; everything else (hosts, words) is compared in lowercase. */
    private inline fun strings(o: JSONObject, key: String, lowercase: Boolean = true, set: (Set<String>) -> Unit) {
        val arr: JSONArray = o.optJSONArray(key) ?: return
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val raw = arr.optString(i, "").trim()
            val v = if (lowercase) raw.lowercase() else raw
            if (v.isNotEmpty() && v.length <= 253) out.add(v)
        }
        set(out)
    }
}
