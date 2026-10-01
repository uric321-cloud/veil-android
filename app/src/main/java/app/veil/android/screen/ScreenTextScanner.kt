package app.veil.android.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** One piece of on-screen text with where it sits and whether it is a password field. */
data class ScannedText(val text: String, val bounds: Rect, val isPassword: Boolean)

/**
 * Walks an accessibility node tree and returns the visible text nodes with their
 * on-screen rectangles. Iterative (an explicit stack) and capped, so a pathological
 * screen can never stall or blow the stack. Runs off the main thread.
 */
object ScreenTextScanner {

    private const val MAX_NODES = 2000

    fun scan(root: AccessibilityNodeInfo?): List<ScannedText> {
        val out = ArrayList<ScannedText>()
        if (root == null) return out
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            try {
                val cs = node.text
                if (!cs.isNullOrEmpty() && node.isVisibleToUser) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) {
                        out.add(ScannedText(cs.toString(), r, node.isPassword))
                    }
                }
                val count = node.childCount
                for (i in 0 until count) {
                    node.getChild(i)?.let { stack.addLast(it) }
                }
            } catch (_: Throwable) {
                // A node can go stale mid-walk; skip it.
            }
        }
        return out
    }
}
