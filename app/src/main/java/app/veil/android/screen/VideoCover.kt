package app.veil.android.screen

/**
 * Real-time video blur. A face in a *moving* video can't be tracked frame by
 * frame without flicker (and our own cover bar hides it from the next capture),
 * so once a person is detected inside a video surface we cover the WHOLE surface
 * and keep it covered while that surface is on screen.
 *
 * Video surfaces are found from accessibility NODES (SurfaceView / TextureView /
 * VideoView), which our overlay does not hide — so "is the video still here" is
 * reliable even while we're covering it, which is what breaks the cover/uncover
 * loop. Pure Kotlin so it is unit-tested on a JVM (.localcheck/test/VideoTest.kt).
 */
object VideoCover {

    /**
     * The video surfaces to cover this frame: any surface that a face overlaps
     * now, or that was flagged on an earlier frame and is still present. [flagged]
     * is updated in place with the surfaces decided so far (sticky memory), so a
     * surface stays covered after our bar hides the face inside it.
     */
    fun cover(faces: List<Box>, surfaces: List<Box>, flagged: MutableList<Box>): List<Box> {
        val out = ArrayList<Box>()
        for (s in surfaces) {
            val hitNow = faces.any { overlaps(it, s) }
            val wasFlagged = flagged.any { overlaps(it, s) }
            if (hitNow || wasFlagged) {
                out.add(s)
                if (flagged.none { overlaps(it, s) }) flagged.add(s)
            }
        }
        // Drop flags whose surface is no longer present (video closed / scrolled away).
        flagged.retainAll { f -> surfaces.any { overlaps(it, f) } }
        return out
    }

    private fun overlaps(a: Box, b: Box): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
