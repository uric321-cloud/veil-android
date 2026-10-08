package app.veil.android.screen

/**
 * On-device risk detection for on-screen text: grooming, sextortion, self-harm
 * and bullying. It returns only the risk CATEGORY(ies) a passage matches — never
 * the text itself — so the admin can be alerted that "possible grooming language
 * was seen" without any message ever leaving the phone. That is the whole point:
 * the intelligence of a monitoring app, with VEIL's content-free privacy.
 *
 * Patterns are deliberately conservative (specific multi-word phrases) to keep
 * false positives low. Pure Kotlin so it is unit-tested on a JVM
 * (.localcheck/test/RiskTest.kt).
 */
object RiskClassifier {

    const val GROOMING = "grooming"
    const val SEXTORTION = "sextortion"
    const val SELF_HARM = "self_harm"
    const val BULLYING = "bullying"

    // Phrase lists are matched as lowercase substrings after light normalization.
    private val PHRASES: List<Pair<String, List<String>>> = listOf(
        GROOMING to listOf(
            "our little secret", "our secret", "don't tell your parents", "dont tell your parents",
            "don't tell your mom", "don't tell your dad", "don't tell anyone", "dont tell anyone",
            "how old are you", "are you home alone", "are you alone", "when are you alone",
            "send me a pic", "send a pic of you", "send me a picture", "send me a selfie",
            "what are you wearing", "delete these messages", "delete this chat", "delete our chat",
            "you're so mature", "youre so mature", "so mature for your age", "don't screenshot",
        ),
        SEXTORTION to listOf(
            "i have your nudes", "i have your photos", "i have your pics", "i have screenshots of you",
            "send money or", "pay me or", "if you don't pay", "if you dont pay",
            "i'll post your", "i will post your", "i'll leak your", "i will leak your",
            "leak your photos", "leak your nudes", "expose you to everyone",
        ),
        SELF_HARM to listOf(
            "kill myself", "killing myself", "want to die", "want to kill myself",
            "end my life", "end it all", "cut myself", "cutting myself", "hurt myself",
            "commit suicide", "suicidal", "no reason to live", "better off dead",
        ),
        BULLYING to listOf(
            "kill yourself", "you should die", "go kill yourself", "you should kill yourself",
            "nobody likes you", "everybody hates you", "everyone hates you",
            "you're worthless", "you are worthless", "go die", "you're a loser and",
        ),
    )

    // Short tokens need word boundaries so "kys" doesn't match "skys" etc.
    private val WORD_REGEX: List<Pair<String, Regex>> = listOf(
        BULLYING to Regex("\\bkys\\b"),
    )

    /** The risk categories [text] matches, or an empty set. Never returns the text. */
    fun categories(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        val t = text.lowercase().replace('\n', ' ')
        val out = LinkedHashSet<String>()
        for ((cat, phrases) in PHRASES) {
            if (phrases.any { t.contains(it) }) out.add(cat)
        }
        for ((cat, rx) in WORD_REGEX) {
            if (rx.containsMatchIn(t)) out.add(cat)
        }
        return out
    }
}
