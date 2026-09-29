package app.veil.android.rules

import android.content.Context
import android.content.SharedPreferences
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

    fun addKeyword(word: String): Boolean {
        val w = word.trim().lowercase(Locale.ROOT)
        if (w.length < 3 || w.any { it.isWhitespace() }) return false
        keywords = keywords + w
        return true
    }

    fun removeKeyword(word: String) { keywords = keywords - word }

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
        append(" pin=").append(hasPin)
        append(" listCount=").append(listDomainCount).append(" listUpdatedAt=").append(listUpdatedAt)
        append(" privateDns='").append(privateDnsHost).append('\'')
        append(" blockedToday=").append(blockedToday).append(" blockedTotal=").append(blockedTotal)
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

        /** Keys whose change means the matcher must be rebuilt. */
        val RULE_KEYS = setOf(K_ADULT, K_KEYWORDS_ON, K_SAFESEARCH, K_YT_STRICT, K_BYPASS, K_UPSTREAM_FAMILY, K_BLOCK, K_ALLOW, K_KEYWORDS, K_LIST_UPDATED)

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
