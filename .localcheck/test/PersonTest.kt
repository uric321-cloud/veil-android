import app.veil.android.screen.Box
import app.veil.android.screen.PersonCover

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("  ok   $name") else { failures++; println("  FAIL $name") }
}

fun main() {
    val screen = Box(0, 0, 1000, 2000)

    println("blur every person")
    check("no faces -> no covers", PersonCover.covers(emptyList(), screen).isEmpty())

    // One face in the middle: the cover reaches above the head, well below over
    // the body, and is wider than the face - clamped to the screen.
    val one = PersonCover.covers(listOf(Box(400, 300, 500, 420)), screen)
    check("one face -> one cover", one.size == 1)
    val c = one[0]
    check("cover starts above the face", c.top < 300)
    check("cover reaches down over the body", c.bottom > 1000)
    check("cover is wider than the face", c.left < 400 && c.right > 500)
    check("cover stays inside the screen", c.left >= 0 && c.right <= 1000 && c.bottom <= 2000)

    // A face in the bottom-right corner: the cover is clamped, never off-screen.
    val corner = PersonCover.covers(listOf(Box(950, 1950, 1000, 2000)), screen)[0]
    check("corner cover clamped to screen", corner.right <= 1000 && corner.bottom <= 2000)

    // Two people close together merge into one block...
    val close = PersonCover.covers(listOf(Box(400, 300, 500, 420), Box(620, 300, 720, 420)), screen)
    check("adjacent people merge into one cover", close.size == 1)

    // ...but two far apart stay separate.
    val wide = Box(0, 0, 3000, 2000)
    val far = PersonCover.covers(listOf(Box(400, 300, 500, 420), Box(2000, 300, 2100, 420)), wide)
    check("distant people stay separate", far.size == 2)

    println(if (failures == 0) "ALL PASSED" else "$failures FAILURES")
    if (failures > 0) System.exit(1)
}
