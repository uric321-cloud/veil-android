package app.veil.android.rules

import android.content.Context
import android.content.SharedPreferences
import app.veil.android.screen.Severity
import app.veil.android.screen.TextAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * All user-editable settings and lists, backed by SharedPreferences. Small on
 * purpose: build one has no database. The VPN service listens for changes and
 * reloads its matcher, so edits take effect immediately.
 */
class RuleStore private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("veil", Context.MODE_PRIVATE)

    // ---- protection state ----
    var protectionWanted: Boolean
        get() = prefs.getBoolean(K_WANTED, false)
        set(v) = prefs.edit().putBoolean(K_WANTED, v).apply()

    var lastRevokeAt: Long
        get() = prefs.getLong(K_REVOKE, 0)
        set(v) = prefs.edit().putLong(K_REVOKE, v).apply()

    var privateDnsHost: String
        get() = prefs.getString(K_PRIVATE_DNS, "") ?: ""
        set(v) = prefs.edit().putString(K_PRIVATE_DNS, v).apply()

    // ---- rule toggles ----
    var adultListEnabled: Boolean
        get() = prefs.getBoolean(K_ADULT, true)
        set(v) = prefs.edit().putBoolean(K_ADULT, v).apply()

    var keywordsEnabled: Boolean
        get() = prefs.getBoolean(K_KEYWORDS_ON, true)
        set(v) = prefs.edit().putBoolean(K_KEYWORDS_ON, v).apply()

    var safeSearchEnabled: Boolean
        get() = prefs.getBoolean(K_SAFESEARCH, true)
        set(v) = prefs.edit().putBoolean(K_SAFESEARCH, v).apply()

    var youtubeStrict: Boolean
        get() = prefs.getBoolean(K_YT_STRICT, false)
        set(v) = prefs.edit().putBoolean(K_YT_STRICT, v).apply()

    var bypassProtectionEnabled: Boolean
        get() = prefs.getBoolean(K_BYPASS, true)
        set(v) = prefs.edit().putBoolean(K_BYPASS, v).apply()

    var upstreamFamilyFilter: Boolean
        get() = prefs.getBoolean(K_UPSTREAM_FAMILY, true)
        set(v) = prefs.edit().putBoolean(K_UPSTREAM_FAMILY, v).apply()

    var notifyOnBlock: Boolean
        get() = prefs.getBoolean(K_NOTIFY, true)
        set(v) = prefs.edit().putBoolean(K_NOTIFY, v).apply()

    // ---- lists ----
    var customBlock: Set<String>
        get() = prefs.getStringSet(K_BLOCK, emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_BLOCK, v.toSet()).apply()

    var customAllow: Set<String>
        get() = prefs.getStringSet(K_ALLOW, emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_ALLOW, v.toSet()).apply()

    var keywords: Set<String>
        get() = prefs.getStringSet(K_KEYWORDS, null)?.toSet() ?: DEFAULT_KEYWORDS
        set(v) = prefs.edit().putStringSet(K_KEYWORDS, v.toSet()).apply()

    fun addBlock(host: String): Boolean {
        val h = normalizeHost(host) ?: return false
        customBlock = customBlock + h
        customAllow = customAllow - h
        return true
    }

    fun removeBlock(host: String) { customBlock = customBlock - host }

    fun addAllow(host: String): Boolean {
        val h = normalizeHost(host) ?: return false
        customAllow = customAllow + h
        customBlock = customBlock - h
        return true
    }

    fun removeAllow(host: String) { customAllow = customAllow - host }

    /** Admin-approved temporary allows, host -> expiry (epoch ms). Stored as "host|until". */
    var tempAllows: Map<String, Long>
        get() = (prefs.getStringSet(K_TEMP_ALLOW, emptySet()) ?: emptySet()).mapNotNull { e ->
            val i = e.lastIndexOf('|')
            val until = if (i > 0) e.substring(i + 1).toLongOrNull() else null
            if (until == null) null else e.substring(0, i) to until
        }.toMap()
        set(v) = prefs.edit().putStringSet(K_TEMP_ALLOW, v.map { "${it.key}|${it.value}" }.toSet()).apply()

    fun liveTempAllows(now: Long = System.currentTimeMillis()): Map<String, Long> = tempAllows.filterValues { it > now }

    /** Drops expired temporary allows; the change rebuilds the matcher. Returns true if anything expired. */
    fun pruneTempAllows(now: Long = System.currentTimeMillis()): Boolean {
        val all = tempAllows
        val live = all.filterValues { it > now }
        if (live.size == all.size) return false
        tempAllows = live
        return true
    }

    /** Bumped when a new AI blocklist file is saved, so the matcher reloads it. */
    var aiBlocklistVersion: Int
        get() = prefs.getInt(K_AI_BLOCK_VERSION, 0)
        set(v) = prefs.edit().putInt(K_AI_BLOCK_VERSION, v).apply()

    /** Admin's "allowed sites only" mode: everything else gets NXDOMAIN. */
    var webAllowlistMode: Boolean
        get() = prefs.getBoolean(K_WEB_ALLOWLIST, false)
        set(v) = prefs.edit().putBoolean(K_WEB_ALLOWLIST, v).apply()

    /** In allowlist mode, also allow the AI's shared list of clearly safe sites. */
    var aiAutoAllowSafe: Boolean
        get() = prefs.getBoolean(K_AI_ALLOW_SAFE, true)
        set(v) = prefs.edit().putBoolean(K_AI_ALLOW_SAFE, v).apply()

    var aiClassification: Boolean
        get() = prefs.getBoolean(K_AI_CLASSIFY, true)
        set(v) = prefs.edit().putBoolean(K_AI_CLASSIFY, v).apply()

    fun addKeyword(word: String): Boolean {
        val w = word.trim().lowercase(Locale.ROOT)
        if (w.length < 3 || w.any { it.isWhitespace() }) return false
        keywords = keywords + w
        return true
    }

    fun removeKeyword(word: String) { keywords = keywords - word }

    // ---- screen filter (Stage 1: text redaction) ----
    var screenProtectionWanted: Boolean
        get() = prefs.getBoolean(K_SCREEN_WANTED, false)
        set(v) = prefs.edit().putBoolean(K_SCREEN_WANTED, v).apply()

    var textEnabled: Boolean
        get() = prefs.getBoolean(K_TEXT_ON, true)
        set(v) = prefs.edit().putBoolean(K_TEXT_ON, v).apply()

    /** One of Tiers.YOUNG_CHILD / CHILD / TEEN / ADULT / CUSTOM. */
    var textTier: String
        get() = prefs.getString(K_TEXT_TIER, "child") ?: "child"
        set(v) = prefs.edit().putString(K_TEXT_TIER, v).apply()

    var textWarnLogOnly: Boolean
        get() = prefs.getBoolean(K_TEXT_LOGONLY, false)
        set(v) = prefs.edit().putBoolean(K_TEXT_LOGONLY, v).apply()

    var textDeobfuscate: Boolean
        get() = prefs.getBoolean(K_TEXT_DEOBF, false)
        set(v) = prefs.edit().putBoolean(K_TEXT_DEOBF, v).apply()

    // Custom-tier action per severity, stored as action names ("strike"/"bar"/"frost"/"ignore").
    var customMild: String
        get() = prefs.getString(K_CUSTOM_MILD, "ignore") ?: "ignore"
        set(v) = prefs.edit().putString(K_CUSTOM_MILD, v).apply()
    var customStrong: String
        get() = prefs.getString(K_CUSTOM_STRONG, "strike") ?: "strike"
        set(v) = prefs.edit().putString(K_CUSTOM_STRONG, v).apply()
    var customExplicit: String
        get() = prefs.getString(K_CUSTOM_EXPLICIT, "bar") ?: "bar"
        set(v) = prefs.edit().putString(K_CUSTOM_EXPLICIT, v).apply()

    fun customTierActions(): Map<Severity, TextAction> = mapOf(
        Severity.MILD to actionFromName(customMild),
        Severity.STRONG to actionFromName(customStrong),
        Severity.EXPLICIT to actionFromName(customExplicit)
    )

    var textBlockWords: Set<String>
        get() = prefs.getStringSet(K_TEXT_BLOCK, emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_TEXT_BLOCK, v.toSet()).apply()

    var textAllowWords: Set<String>
        get() = prefs.getStringSet(K_TEXT_ALLOW, emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_TEXT_ALLOW, v.toSet()).apply()

    fun addTextBlockWord(w: String): Boolean {
        val n = w.trim().lowercase(Locale.ROOT)
        if (n.length < 2 || n.any { it.isWhitespace() }) return false
        textBlockWords = textBlockWords + n; textAllowWords = textAllowWords - n; return true
    }
    fun removeTextBlockWord(w: String) { textBlockWords = textBlockWords - w }
    fun addTextAllowWord(w: String): Boolean {
        val n = w.trim().lowercase(Locale.ROOT)
        if (n.length < 2 || n.any { it.isWhitespace() }) return false
        textAllowWords = textAllowWords + n; textBlockWords = textBlockWords - n; return true
    }
    fun removeTextAllowWord(w: String) { textAllowWords = textAllowWords - w }

    /** Packages the screen filter skips entirely (first cheap gate). */
    var safeListApps: Set<String>
        get() = prefs.getStringSet(K_SAFELIST, DEFAULT_SAFELIST)?.toSet() ?: DEFAULT_SAFELIST
        set(v) = prefs.edit().putStringSet(K_SAFELIST, v.toSet()).apply()

    /** Stage 4: cover explicit images on screen (Android 11+). */
    var imageFilter: Boolean
        get() = prefs.getBoolean(K_IMAGE_ON, true)
        set(v) = prefs.edit().putBoolean(K_IMAGE_ON, v).apply()

    /** "low" (only clearly explicit), "medium", "high" (also suggestive), "max" (fail-closed). */
    var imageStrictness: String
        get() = prefs.getString(K_IMAGE_STRICT, "medium") ?: "medium"
        set(v) = prefs.edit().putString(K_IMAGE_STRICT, v).apply()

    /**
     * Blur every person on screen: when a face is detected, cover the person from
     * the head down over the body, whatever they are wearing. The strongest
     * posture (and the reliable one for lingerie/swimwear, which the explicit-image
     * model treats as "clothed"); it also covers ordinary photos of people. On by
     * default wherever image filtering is on.
     */
    var blurPeople: Boolean
        get() = prefs.getBoolean(K_BLUR_PEOPLE, true)
        set(v) = prefs.edit().putBoolean(K_BLUR_PEOPLE, v).apply()

    /**
     * Watch on-screen text for grooming / sextortion / self-harm / bullying and
     * alert the admin with the CATEGORY only (never the message). On by default.
     */
    var riskDetection: Boolean
        get() = prefs.getBoolean(K_RISK_DETECT, true)
        set(v) = prefs.edit().putBoolean(K_RISK_DETECT, v).apply()

    /** Cancel incoming notifications whose text contains blocked words. */
    var notificationFilter: Boolean
        get() = prefs.getBoolean(K_NOTIF_ON, true)
        set(v) = prefs.edit().putBoolean(K_NOTIF_ON, v).apply()

    /** Read the browser address bar and block pages by URL path/query (needs the screen filter). */
    var urlFilter: Boolean
        get() = prefs.getBoolean(K_URL_ON, true)
        set(v) = prefs.edit().putBoolean(K_URL_ON, v).apply()

    /** Close browsers whose address bar VEIL can't read, since it can't filter inside them. */
    var blockUnknownBrowsers: Boolean
        get() = prefs.getBoolean(K_URL_BLOCK_UNKNOWN, false)
        set(v) = prefs.edit().putBoolean(K_URL_BLOCK_UNKNOWN, v).apply()

    /** Admin URL substrings to block (path-level), e.g. "reddit.com/r/" or "/explore". */
    var blockedUrls: Set<String>
        get() = prefs.getStringSet(K_URL_PARTS, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_URL_PARTS, v.toSet()).apply()

    /** Downtime / bedtime: when active, all non-essential apps are blocked. */
    var downtimeEnabled: Boolean
        get() = prefs.getBoolean(K_DT_ON, false)
        set(v) = prefs.edit().putBoolean(K_DT_ON, v).apply()

    /** Window start, minutes since midnight (default 21:00). */
    var downtimeStart: Int
        get() = prefs.getInt(K_DT_START, 21 * 60)
        set(v) = prefs.edit().putInt(K_DT_START, v).apply()

    /** Window end, minutes since midnight (default 07:00). */
    var downtimeEnd: Int
        get() = prefs.getInt(K_DT_END, 7 * 60)
        set(v) = prefs.edit().putInt(K_DT_END, v).apply()

    /** Days the window starts on, as "0".."6" (0=Sunday). Default every day. */
    var downtimeDays: Set<String>
        get() = prefs.getStringSet(K_DT_DAYS, setOf("0", "1", "2", "3", "4", "5", "6")) ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_DT_DAYS, v.toSet()).apply()

    /** Version of the proactive site catalog this phone has applied. */
    var catalogVersion: String
        get() = prefs.getString(K_CATALOG_VER, "") ?: ""
        set(v) = prefs.edit().putString(K_CATALOG_VER, v).apply()

    /** Proactive site catalog rules, each encoded "domain\u0001contains\u0001b|a\u0001category". */
    var catalogRules: Set<String>
        get() = prefs.getStringSet(K_CATALOG, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_CATALOG, v.toSet()).apply()

    /** Per-app daily limits: package -> minutes allowed per day. Blocks the app once reached. */
    var appTimeLimits: Map<String, Int>
        get() = (prefs.getStringSet(K_APP_LIMITS, emptySet()) ?: emptySet()).mapNotNull {
            val i = it.lastIndexOf(':'); if (i <= 0) return@mapNotNull null
            val pkg = it.substring(0, i); val m = it.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
            pkg to m
        }.toMap()
        set(v) = prefs.edit().putStringSet(K_APP_LIMITS, v.entries.map { "${it.key}:${it.value}" }.toSet()).apply()

    val imagesCoveredTotal: Long get() = prefs.getLong(K_IMAGE_TOTAL, 0)
    fun countImagesCovered(n: Int) {
        if (n <= 0) return
        prefs.edit().putLong(K_IMAGE_TOTAL, prefs.getLong(K_IMAGE_TOTAL, 0) + n).apply()
    }

    val textCoveredTotal: Long get() = prefs.getLong(K_TEXT_TOTAL, 0)
    fun countTextCovered(n: Int) {
        if (n <= 0) return
        prefs.edit().putLong(K_TEXT_TOTAL, prefs.getLong(K_TEXT_TOTAL, 0) + n).apply()
    }

    private fun actionFromName(name: String): TextAction = when (name.lowercase()) {
        "strike" -> TextAction.STRIKE
        "bar" -> TextAction.BAR
        "frost" -> TextAction.FROST
        "ignore" -> TextAction.IGNORE
        else -> TextAction.BAR
    }

    // ---- list update metadata ----
    var listUpdatedAt: Long
        get() = prefs.getLong(K_LIST_UPDATED, 0)
        set(v) = prefs.edit().putLong(K_LIST_UPDATED, v).apply()

    var listDomainCount: Int
        get() = prefs.getInt(K_LIST_COUNT, 0)
        set(v) = prefs.edit().putInt(K_LIST_COUNT, v).apply()

    // ---- counters ----
    val blockedTotal: Long get() = prefs.getLong(K_TOTAL, 0)

    val blockedToday: Long
        get() = if (prefs.getString(K_TODAY_DATE, "") == today()) prefs.getLong(K_TODAY, 0) else 0

    fun countBlock() {
        val t = today()
        val todayCount = if (prefs.getString(K_TODAY_DATE, "") == t) prefs.getLong(K_TODAY, 0) else 0
        prefs.edit()
            .putLong(K_TOTAL, prefs.getLong(K_TOTAL, 0) + 1)
            .putString(K_TODAY_DATE, t)
            .putLong(K_TODAY, todayCount + 1)
            .apply()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ---- PIN ----
    val hasPin: Boolean get() = !prefs.getString(K_PIN_HASH, "").isNullOrEmpty()

    fun setPin(pin: String?) {
        if (pin.isNullOrEmpty()) {
            prefs.edit().remove(K_PIN_HASH).remove(K_PIN_SALT).apply()
            return
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        prefs.edit().putString(K_PIN_SALT, salt).putString(K_PIN_HASH, hash(salt, pin)).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val salt = prefs.getString(K_PIN_SALT, "") ?: ""
        val expected = prefs.getString(K_PIN_HASH, "") ?: ""
        if (expected.isEmpty()) return true
        return hash(salt, pin) == expected
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray()).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(l)
    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(l)

    /** Snapshot of the settings for the diagnostics report. */
    fun describe(): String = buildString {
        append("protectionWanted=").append(protectionWanted)
        append(" adult=").append(adultListEnabled)
        append(" keywords=").append(keywordsEnabled).append('/').append(keywords.size)
        append(" safeSearch=").append(safeSearchEnabled).append(" ytStrict=").append(youtubeStrict)
        append(" bypassProtection=").append(bypassProtectionEnabled)
        append(" upstreamFamily=").append(upstreamFamilyFilter)
        append(" customBlock=").append(customBlock.size).append(" customAllow=").append(customAllow.size)
        append(" allowlistMode=").append(webAllowlistMode)
        append(" tempAllow=").append(liveTempAllows().size).append(" aiBlocklistVersion=").append(aiBlocklistVersion)
        append(" pin=").append(hasPin)
        append(" listCount=").append(listDomainCount).append(" listUpdatedAt=").append(listUpdatedAt)
        append(" privateDns='").append(privateDnsHost).append('\'')
        append(" blockedToday=").append(blockedToday).append(" blockedTotal=").append(blockedTotal)
        append(" | screen=").append(screenProtectionWanted).append(" text=").append(textEnabled)
        append(" tier=").append(textTier).append(" logOnly=").append(textWarnLogOnly)
        append(" deobf=").append(textDeobfuscate)
        append(" textBlock=").append(textBlockWords.size).append(" textAllow=").append(textAllowWords.size)
        append(" safeList=").append(safeListApps.size).append(" textCovered=").append(textCoveredTotal)
        append(" images=").append(imageFilter).append('/').append(imageStrictness).append(" imagesCovered=").append(imagesCoveredTotal)
    }

    companion object {
        @Volatile private var instance: RuleStore? = null

        fun get(context: Context): RuleStore =
            instance ?: synchronized(this) { instance ?: RuleStore(context.applicationContext).also { instance = it } }

        const val K_WANTED = "protection_wanted"
        const val K_REVOKE = "last_revoke_at"
        const val K_PRIVATE_DNS = "private_dns_host"
        const val K_ADULT = "adult_list_enabled"
        const val K_KEYWORDS_ON = "keywords_enabled"
        const val K_SAFESEARCH = "safe_search_enabled"
        const val K_YT_STRICT = "youtube_strict"
        const val K_BYPASS = "bypass_protection_enabled"
        const val K_UPSTREAM_FAMILY = "upstream_family_filter"
        const val K_NOTIFY = "notify_on_block"
        const val K_BLOCK = "custom_block"
        const val K_ALLOW = "custom_allow"
        const val K_KEYWORDS = "keywords"
        const val K_LIST_UPDATED = "list_updated_at"
        const val K_LIST_COUNT = "list_domain_count"
        const val K_TOTAL = "blocked_total"
        const val K_TODAY = "blocked_today"
        const val K_TODAY_DATE = "blocked_today_date"
        const val K_PIN_HASH = "pin_hash"
        const val K_PIN_SALT = "pin_salt"
        const val K_TEMP_ALLOW = "temp_allow"
        const val K_AI_BLOCK_VERSION = "ai_blocklist_version"
        const val K_AI_CLASSIFY = "ai_classification"
        const val K_WEB_ALLOWLIST = "web_allowlist_mode"
        const val K_AI_ALLOW_SAFE = "ai_auto_allow_safe"

        // ---- screen filter keys ----
        const val K_SCREEN_WANTED = "screen_protection_wanted"
        const val K_TEXT_ON = "text_enabled"
        const val K_TEXT_TIER = "text_tier"
        const val K_TEXT_LOGONLY = "text_warn_log_only"
        const val K_TEXT_DEOBF = "text_deobfuscate"
        const val K_CUSTOM_MILD = "text_custom_mild"
        const val K_CUSTOM_STRONG = "text_custom_strong"
        const val K_CUSTOM_EXPLICIT = "text_custom_explicit"
        const val K_TEXT_BLOCK = "text_block_words"
        const val K_TEXT_ALLOW = "text_allow_words"
        const val K_SAFELIST = "safe_list_apps"
        const val K_TEXT_TOTAL = "text_covered_total"
        const val K_IMAGE_ON = "image_filter"
        const val K_NOTIF_ON = "notification_filter"
        const val K_URL_ON = "url_filter"
        const val K_URL_BLOCK_UNKNOWN = "url_block_unknown_browsers"
        const val K_URL_PARTS = "url_blocked_parts"
        const val K_DT_ON = "downtime_enabled"
        const val K_DT_START = "downtime_start"
        const val K_DT_END = "downtime_end"
        const val K_DT_DAYS = "downtime_days"
        const val K_APP_LIMITS = "app_time_limits"
        const val K_CATALOG_VER = "catalog_version"
        const val K_CATALOG = "catalog_rules"
        const val K_IMAGE_STRICT = "image_strictness"
        const val K_BLUR_PEOPLE = "blur_people"
        const val K_RISK_DETECT = "risk_detection"
        const val K_IMAGE_TOTAL = "images_covered_total"

        /** Keys whose change means the DNS matcher must be rebuilt. */
        val RULE_KEYS = setOf(K_ADULT, K_KEYWORDS_ON, K_SAFESEARCH, K_YT_STRICT, K_BYPASS, K_UPSTREAM_FAMILY, K_BLOCK, K_ALLOW, K_KEYWORDS, K_LIST_UPDATED, K_TEMP_ALLOW, K_AI_BLOCK_VERSION, K_WEB_ALLOWLIST, K_AI_ALLOW_SAFE)

        /** Keys whose change means the text engine must be rebuilt. */
        val SCREEN_RULE_KEYS = setOf(K_TEXT_ON, K_TEXT_TIER, K_TEXT_LOGONLY, K_TEXT_DEOBF, K_CUSTOM_MILD, K_CUSTOM_STRONG, K_CUSTOM_EXPLICIT, K_TEXT_BLOCK, K_TEXT_ALLOW, K_SAFELIST, K_IMAGE_ON, K_IMAGE_STRICT, K_NOTIF_ON)

        /** Apps never scanned: VEIL itself plus common safe system apps. */
        val DEFAULT_SAFELIST: Set<String> = setOf(
            "app.veil.android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.deskclock",
            "com.google.android.deskclock"
        )

        val DEFAULT_KEYWORDS: Set<String> = setOf(
            "porn", "xxx", "hentai", "xvideos", "xnxx", "xhamster", "redtube", "youporn",
            "pornhub", "onlyfans", "nsfw", "chaturbate", "stripchat", "livejasmin",
            "brazzers", "rule34", "nhentai", "e-hentai", "fapello", "camgirl", "erome"
        )

        /**
         * Turns whatever the user typed ("https://www.Example.com/path") into a
         * bare lowercase host ("example.com"). Returns null if nothing usable is left.
         */
        fun normalizeHost(input: String): String? {
            var s = input.trim().lowercase(Locale.ROOT)
            if (s.isEmpty()) return null
            s = s.removePrefix("http://").removePrefix("https://")
            s = s.substringBefore('/').substringBefore('?').substringBefore('#')
            s = s.substringBefore(':')
            s = s.removePrefix("*.").removePrefix("www.").trim('.')
            if (s.isEmpty() || !s.contains('.')) return null
            if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
            return s
        }
    }
}
