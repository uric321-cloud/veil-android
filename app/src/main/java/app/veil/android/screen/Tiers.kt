package app.veil.android.screen

/**
 * The strictness tiers for text. Each maps a severity to a text action, and
 * declares which categories are active. Custom reads its three actions from
 * settings. These mirror the "Strictness tiers" section of the architecture spec.
 */
object Tiers {

    val ALL_CATEGORIES = setOf("profanity", "sexual", "slurs", "selfharm", "violence")

    /** Tier ids as stored in settings. */
    const val YOUNG_CHILD = "young_child"
    const val CHILD = "child"
    const val TEEN = "teen"
    const val ADULT = "adult"
    const val CUSTOM = "custom"

    val IDS = listOf(YOUNG_CHILD, CHILD, TEEN, ADULT, CUSTOM)

    fun label(id: String): String = when (id) {
        YOUNG_CHILD -> "Young child"
        CHILD -> "Child"
        TEEN -> "Teen"
        ADULT -> "Adult (self-filter)"
        CUSTOM -> "Custom"
        else -> id
    }

    /** The severity->action map for a built-in tier. Custom is supplied separately. */
    fun actions(id: String): Map<Severity, TextAction> = when (id) {
        YOUNG_CHILD -> mapOf(
            Severity.MILD to TextAction.BAR,
            Severity.STRONG to TextAction.BAR,
            Severity.EXPLICIT to TextAction.BAR
        )
        CHILD -> mapOf(
            Severity.MILD to TextAction.STRIKE,
            Severity.STRONG to TextAction.BAR,
            Severity.EXPLICIT to TextAction.BAR
        )
        TEEN -> mapOf(
            Severity.MILD to TextAction.IGNORE,
            Severity.STRONG to TextAction.STRIKE,
            Severity.EXPLICIT to TextAction.BAR
        )
        ADULT -> mapOf(
            Severity.MILD to TextAction.IGNORE,
            Severity.STRONG to TextAction.IGNORE,
            Severity.EXPLICIT to TextAction.STRIKE
        )
        else -> mapOf( // sensible default if an unknown id slips through
            Severity.MILD to TextAction.IGNORE,
            Severity.STRONG to TextAction.STRIKE,
            Severity.EXPLICIT to TextAction.BAR
        )
    }

    /** Categories active for a built-in tier. */
    fun categories(id: String): Set<String> = when (id) {
        ADULT -> setOf("sexual", "slurs")
        else -> ALL_CATEGORIES
    }
}
