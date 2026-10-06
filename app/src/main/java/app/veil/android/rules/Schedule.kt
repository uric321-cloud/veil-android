package app.veil.android.rules

/**
 * Downtime / bedtime schedule decision: a daily window during which all
 * non-essential apps are blocked. Pure (no Android types) so it is unit-tested
 * on the JVM (.localcheck/test/ScheduleTest.kt). Times are minutes-since-
 * midnight; days are 0=Sunday .. 6=Saturday and mark the day a window *starts*
 * on, so a window that crosses midnight still blocks the early-morning hours.
 */
object Schedule {

    /**
     * Is the downtime window active at [dow] (0=Sun) and [minute] (minutes since
     * midnight)? Handles windows that cross midnight (start >= end).
     */
    fun activeAt(enabled: Boolean, startMin: Int, endMin: Int, days: Set<Int>, dow: Int, minute: Int): Boolean {
        if (!enabled || days.isEmpty() || startMin == endMin) return false
        return if (startMin < endMin) {
            dow in days && minute >= startMin && minute < endMin
        } else {
            // Crosses midnight: the evening part belongs to today's selection,
            // the after-midnight part to the previous day's selection.
            (dow in days && minute >= startMin) || (((dow + 6) % 7) in days && minute < endMin)
        }
    }
}
