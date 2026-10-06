import app.veil.android.rules.Schedule

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("  ok   $name") else { failures++; println("  FAIL $name") }
}

// Days: 0=Sun..6=Sat. Times in minutes since midnight.
fun main() {
    val everyDay = setOf(0, 1, 2, 3, 4, 5, 6)

    println("disabled / empty")
    check("disabled is never active", !Schedule.activeAt(false, 1260, 420, everyDay, 3, 1300))
    check("no days is never active", !Schedule.activeAt(true, 1260, 420, emptySet(), 3, 1300))
    check("zero-length window never active", !Schedule.activeAt(true, 600, 600, everyDay, 3, 600))

    println("same-day window 09:00-17:00")
    val work = setOf(1, 2, 3, 4, 5)
    check("inside on a weekday", Schedule.activeAt(true, 540, 1020, work, 3, 600))
    check("before start not active", !Schedule.activeAt(true, 540, 1020, work, 3, 500))
    check("at end not active (exclusive)", !Schedule.activeAt(true, 540, 1020, work, 3, 1020))
    check("not active on a non-selected day", !Schedule.activeAt(true, 540, 1020, work, 0, 600))

    println("overnight window 21:00-07:00")
    // selected day marks the night's start (the evening)
    check("22:00 Fri active (Fri selected)", Schedule.activeAt(true, 1260, 420, setOf(5), 5, 1320))
    check("02:00 Sat active (belongs to Fri night)", Schedule.activeAt(true, 1260, 420, setOf(5), 6, 120))
    check("08:00 Sat not active", !Schedule.activeAt(true, 1260, 420, setOf(5), 6, 480))
    check("02:00 Fri not active when only Fri selected", !Schedule.activeAt(true, 1260, 420, setOf(5), 5, 120))
    check("20:00 Fri not active (before start)", !Schedule.activeAt(true, 1260, 420, setOf(5), 5, 1200))
    check("every night: 02:00 any day active", Schedule.activeAt(true, 1260, 420, everyDay, 2, 120))

    println(if (failures == 0) "ALL PASSED" else "$failures FAILURES")
    if (failures > 0) System.exit(1)
}
