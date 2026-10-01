package app.veil.android.screen

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import app.veil.android.VeilLog

/**
 * Owns the single transparent overlay window and its view. All methods must be
 * called on the main thread (WindowManager and View require it). The overlay is
 * added lazily on first use and removed on hide().
 */
class OverlayController(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: RedactionOverlayView? = null

    private fun ensureShown() {
        if (view != null) return
        try {
            val v = RedactionOverlayView(context)
            // minSdk is 26, so TYPE_APPLICATION_OVERLAY is always available.
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            wm.addView(v, params)
            view = v
        } catch (t: Throwable) {
            VeilLog.e("Overlay addView failed (missing permission?)", t)
        }
    }

    fun update(covers: List<Cover>) {
        if (covers.isEmpty()) { clear(); return }
        ensureShown()
        view?.setCovers(covers)
    }

    fun clear() {
        view?.setCovers(emptyList())
    }

    fun hide() {
        val v = view ?: return
        view = null
        try { wm.removeView(v) } catch (_: Throwable) {}
    }
}
