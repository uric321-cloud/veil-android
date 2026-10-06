package app.veil.android.screen

/**
 * The matching rule for in-app blocking, free of Android types so it can be
 * unit tested on a plain JVM (.localcheck/test/InAppTest.kt). Every field the
 * rule sets must match, case-insensitively: view id and description by
 * "contains" (the view id without its "package:id/" prefix), text exactly.
 * Rule fields are expected in lowercase already.
 */
object InAppMatch {
    fun matches(
        ruleViewId: String?, ruleText: String?, ruleDesc: String?, ruleSelected: Boolean,
        viewId: String?, text: String?, desc: String?, selected: Boolean
    ): Boolean {
        if (ruleViewId != null && (viewId == null || !viewId.lowercase().substringAfter(":id/").contains(ruleViewId))) return false
        if (ruleText != null && (text == null || text.trim().lowercase() != ruleText)) return false
        if (ruleDesc != null && (desc == null || !desc.lowercase().contains(ruleDesc))) return false
        if (ruleSelected && !selected) return false
        return true
    }
}
