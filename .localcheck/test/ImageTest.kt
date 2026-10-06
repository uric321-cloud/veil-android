import app.veil.android.screen.ImageVerdict

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("  ok   $name") else { failures++; println("  FAIL $name") }
}

// Drawing, Hentai, Neutral, Porn, Sexy
fun s(d: Float = 0f, h: Float = 0f, n: Float = 0f, p: Float = 0f, x: Float = 0f) = floatArrayOf(d, h, n, p, x)

fun main() {
    println("neutral and drawings")
    for (level in listOf("low", "medium", "high")) {
        check("neutral photo stays ($level)", !ImageVerdict.shouldCover(s(n = 0.95f, x = 0.03f), level))
        check("cartoon stays ($level)", !ImageVerdict.shouldCover(s(d = 0.9f, n = 0.08f), level))
    }
    println("explicit")
    check("clear porn covered at low", ImageVerdict.shouldCover(s(p = 0.9f, x = 0.05f), "low"))
    check("porn + hentai add up", ImageVerdict.shouldCover(s(h = 0.35f, p = 0.35f, n = 0.3f), "medium"))
    check("borderline explicit not covered at low", !ImageVerdict.shouldCover(s(p = 0.7f, n = 0.3f), "low"))
    println("suggestive")
    check("swimsuit-level sexy left at medium", !ImageVerdict.shouldCover(s(x = 0.7f, n = 0.3f), "medium"))
    check("swimsuit-level sexy covered at high", ImageVerdict.shouldCover(s(x = 0.7f, n = 0.3f), "high"))
    check("unknown strictness behaves like medium", ImageVerdict.shouldCover(s(p = 0.65f), "???"))
    check("short score array is ignored", !ImageVerdict.shouldCover(floatArrayOf(0.9f), "high"))

    println(if (failures == 0) "ALL PASSED" else "$failures FAILURES")
    if (failures > 0) System.exit(1)
}
