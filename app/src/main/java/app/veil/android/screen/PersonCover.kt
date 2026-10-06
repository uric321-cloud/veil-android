package app.veil.android.screen

/**
 * A plain integer rectangle, so the cover geometry is pure Kotlin and unit-tests
 * on a JVM without android.graphics.Rect (whose methods are stubbed to throw in
 * the offline test runtime). PersonScanner converts to/from android Rect.
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
}

/**
 * "Blur every person": turns detected face boxes into the regions to cover. From
 * each face we cover the head and the body beneath it (neck-down) — a tall block
 * roughly three face-widths wide reaching well past the torso — then overlapping
 * blocks are merged so one person is a single cover, not a stack of rectangles.
 *
 * Pure Kotlin so it is unit-tested on a JVM (.localcheck/test/PersonCoverTest.kt).
 */
object PersonCover {

    // How far, in multiples of the face box, a cover extends around the face.
    private const val SIDE = 1.5     // widen each side by 1.5x the face width (shoulders/arms)
    private const val UP = 0.7       // a little above the hairline
    private const val DOWN = 8.0     // head + ~8 face-heights of body, clamped to the screen

    fun covers(faces: List<Box>, screen: Box): List<Box> {
        if (faces.isEmpty()) return emptyList()
        val raw = ArrayList<Box>(faces.size)
        for (f in faces) {
            val w = f.width.coerceAtLeast(1)
            val h = f.height.coerceAtLeast(1)
            val left = (f.centerX - (w * SIDE).toInt()).coerceAtLeast(screen.left)
            val right = (f.centerX + (w * SIDE).toInt()).coerceAtMost(screen.right)
            val top = (f.top - (h * UP).toInt()).coerceAtLeast(screen.top)
            val bottom = (f.top + (h * DOWN).toInt()).coerceAtMost(screen.bottom)
            if (right > left && bottom > top) raw.add(Box(left, top, right, bottom))
        }
        return merge(raw)
    }

    /** Unions boxes that overlap or touch, repeatedly, so people merge into one block. */
    private fun merge(boxes: List<Box>): List<Box> {
        val list = boxes.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    if (touches(list[i], list[j])) {
                        list[i] = union(list[i], list[j])
                        list.removeAt(j)
                        changed = true
                        break@loop
                    }
                }
            }
        }
        return list
    }

    private fun touches(a: Box, b: Box): Boolean =
        a.left <= b.right && b.left <= a.right && a.top <= b.bottom && b.top <= a.bottom

    private fun union(a: Box, b: Box): Box =
        Box(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
}
