import app.veil.android.screen.Box
import app.veil.android.screen.VideoCover

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("  ok   $name") else { failures++; println("  FAIL $name") }
}

fun main() {
    val surface = Box(0, 200, 1000, 800)       // a video player area
    val faceInside = Box(400, 300, 500, 420)
    val faceOutside = Box(400, 900, 500, 1020)

    println("real-time video blur")

    // A face inside the video → the whole surface is covered, and it's flagged.
    run {
        val flagged = ArrayList<Box>()
        val out = VideoCover.cover(listOf(faceInside), listOf(surface), flagged)
        check("face in video covers whole surface", out == listOf(surface))
        check("surface is flagged (sticky)", flagged.size == 1)

        // Next frame: our bar now hides the face, so no face is detected — but the
        // surface is still present, so it stays covered (no flicker).
        val out2 = VideoCover.cover(emptyList(), listOf(surface), flagged)
        check("stays covered while surface is present", out2 == listOf(surface))

        // The video closes (surface gone) → nothing covered, flag cleared.
        val out3 = VideoCover.cover(emptyList(), emptyList(), flagged)
        check("clears when the video leaves the screen", out3.isEmpty() && flagged.isEmpty())
    }

    // A face NOT over any video surface → no video cover.
    run {
        val flagged = ArrayList<Box>()
        val out = VideoCover.cover(listOf(faceOutside), listOf(surface), flagged)
        check("face outside the video: no video cover", out.isEmpty() && flagged.isEmpty())
    }

    // No faces, no surfaces → empty.
    check("nothing on screen: no cover", VideoCover.cover(emptyList(), emptyList(), ArrayList()).isEmpty())

    println(if (failures == 0) "ALL PASSED" else "$failures FAILURES")
    if (failures > 0) System.exit(1)
}
