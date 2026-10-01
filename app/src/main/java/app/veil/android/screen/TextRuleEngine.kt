package app.veil.android.screen

import java.util.Locale

/** How severe a flagged term is. Drives what the tier decides to do about it. */
enum class Severity { MILD, STRONG, EXPLICIT }

/** What to do with a flagged span. STRIKE/BAR/FROST are the three covers. */
enum class TextAction { IGNORE, STRIKE, BAR, FROST, LOG }

/** One flagged span in the original text, with the action to take. */
data class Redaction(
    val start: Int,
    val end: Int,
    val action: TextAction,
    val severity: Severity,
    val category: String
)

/**
 * Pure, Android-free text matcher. Given a compiled rule set and a tier's
 * severity->action map, it scans a string and returns the spans to cover.
 * No I/O, no framework types, so it is unit-tested directly on the JVM.
 */
class TextRuleEngine(
    /** normalized single word -> (category, severity) */
    private val termIndex: Map<String, Pair<String, Severity>>,
    /** multi-word phrases, already lowercased, as (phrase, category, severity) */
    private val phrases: List<Triple<String, String, Severity>>,
    private val tierActions: Map<Severity, TextAction>,
    private val enabledCategories: Set<String>,
    private val deobfuscate: Boolean,
    private val logOnly: Boolean,
    /** words the key-holder has explicitly allowed; never flagged */
    private val allow: Set<String> = emptySet()
) {

    /** Returns the spans to redact, in order of appearance. Overlapping spans are merged by the caller. */
    fun scan(text: String): List<Redaction> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Redaction>()
        scanWords(text, out)
        scanPhrases(text, out)
        return out
    }

    private fun scanWords(text: String, out: MutableList<Redaction>) {
        var i = 0
        val n = text.length
        while (i < n) {
            if (!isWordChar(text[i])) { i++; continue }
            val start = i
            while (i < n && isWordChar(text[i])) i++
            val raw = text.substring(start, i)
            val norm = normalize(raw)
            if (norm.isEmpty() || norm in allow) continue
            var hit = termIndex[norm]
            if (hit == null && deobfuscate) hit = termIndex[deob(norm)]
            if (hit != null && hit.first in enabledCategories) {
                val action = resolve(hit.second)
                if (action != TextAction.IGNORE) {
                    out.add(Redaction(start, i, action, hit.second, hit.first))
                }
            }
        }
    }

    private fun scanPhrases(text: String, out: MutableList<Redaction>) {
        if (phrases.isEmpty()) return
        val lower = text.lowercase(Locale.ROOT)
        for ((phrase, category, severity) in phrases) {
            if (category !in enabledCategories) continue
            if (phrase in allow) continue
            val action = resolve(severity)
            if (action == TextAction.IGNORE) continue
            var idx = lower.indexOf(phrase)
            while (idx >= 0) {
                out.add(Redaction(idx, idx + phrase.length, action, severity, category))
                idx = lower.indexOf(phrase, idx + phrase.length)
            }
        }
    }

    /** Tier action for a severity, downgraded to LOG when warn-and-log mode is on. */
    private fun resolve(severity: Severity): TextAction {
        val base = tierActions[severity] ?: TextAction.IGNORE
        if (base == TextAction.IGNORE) return TextAction.IGNORE
        return if (logOnly) TextAction.LOG else base
    }

    companion object {
        /** A word character for tokenizing: letters, digits, and an inner apostrophe. */
        fun isWordChar(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '\''

        /** Lowercase and strip anything that isn't a letter or digit from both ends. */
        fun normalize(raw: String): String {
            var s = raw.lowercase(Locale.ROOT)
            var a = 0
            var b = s.length
            while (a < b && !Character.isLetterOrDigit(s[a])) a++
            while (b > a && !Character.isLetterOrDigit(s[b - 1])) b--
            return s.substring(a, b)
        }

        private val LEET = mapOf(
            '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a',
            '5' to 's', '7' to 't', '@' to 'a', '$' to 's', '!' to 'i'
        )

        /** De-obfuscate: map common leet characters and collapse runs of 3+ identical chars to one. */
        fun deob(s: String): String {
            val mapped = StringBuilder(s.length)
            for (c in s) mapped.append(LEET[c] ?: c)
            val collapsed = StringBuilder(mapped.length)
            var run = 0
            var prev = '\u0000'
            for (c in mapped) {
                if (c == prev) run++ else { run = 1; prev = c }
                if (run == 1) collapsed.append(c) // collapse any repeat run to a single char
            }
            return collapsed.toString()
        }
    }
}
