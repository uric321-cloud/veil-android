package app.veil.android.screen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/** A rectangle on screen and how to cover it. */
data class Cover(val rect: Rect, val action: TextAction)

/**
 * A transparent, full-screen view that paints the current covers. It is purely a
 * painter: it holds a list of covers and redraws when the list changes. No input
 * handling (the window is not touchable), so taps pass through to the app beneath.
 */
class RedactionOverlayView(context: Context) : View(context) {

    @Volatile private var covers: List<Cover> = emptyList()

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F21B1F2A") }
    private val frost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C8E9ECF5") }
    private val strike = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E62F6BFF"); strokeCap = Paint.Cap.ROUND
    }

    fun setCovers(list: List<Cover>) {
        covers = list
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = covers
        if (current.isEmpty()) return
        for (cv in current) {
            val r = cv.rect
            val radius = (r.height() * 0.18f).coerceIn(4f, 14f)
            when (cv.action) {
                TextAction.BAR ->
                    canvas.drawRoundRect(RectF(r), radius, radius, bar)
                TextAction.FROST ->
                    canvas.drawRoundRect(RectF(r), radius, radius, frost)
                TextAction.STRIKE -> {
                    strike.strokeWidth = (r.height() * 0.12f).coerceIn(3f, 8f)
                    val y = r.exactCenterY()
                    canvas.drawLine(r.left.toFloat(), y, r.right.toFloat(), y, strike)
                }
                else -> { /* IGNORE / LOG draw nothing */ }
            }
        }
    }
}
