package app.veil.android.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import app.veil.android.VeilLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor

/**
 * "Blur every person" layer of the screen filter. Takes a screenshot through the
 * accessibility service, finds faces with ML Kit's on-device face detector
 * (a bundled model — no network, no Google Play Services needed), and turns each
 * face into a head-to-body cover via [PersonCover]. Nothing leaves the phone: no
 * screenshot or face data is stored or sent.
 *
 * This runs on its own screenshot (not the image classifier's), because the
 * classifier only shoots when it finds image nodes — and a web page in a browser
 * often exposes none, which is exactly where a person must still be covered.
 */
class PersonScanner(
    private val service: AccessibilityService,
    private val executor: Executor,
    private val onCovers: (List<Rect>) -> Unit,
) {
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.04f) // catch small faces (thumbnails, group shots) too
                .build()
        )
    }

    @Volatile private var busy = false
    @Volatile private var lastRunAt = 0L

    /** The rectangles to cover from the last scan. */
    @Volatile var covers: List<Rect> = emptyList()
        private set

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 30

    /** Forget covers: the screen content changed. */
    fun reset() { covers = emptyList() }

    /** Take a screenshot and cover every person in it. Rate-limited and non-reentrant. */
    fun scan() {
        if (!supported || busy) return
        val now = System.currentTimeMillis()
        if (now - lastRunAt < MIN_INTERVAL_MS) return
        busy = true
        lastRunAt = now
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = result.hardwareBuffer
                        val shot = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        hw.close()
                        if (shot == null) { busy = false; return }
                        detect(shot)
                    } catch (t: Throwable) {
                        busy = false
                        VeilLog.w("Person scan failed: ${t.message}")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    busy = false // e.g. too soon after another screenshot; the next event retries
                }
            })
        } catch (t: Throwable) {
            busy = false
            VeilLog.w("Screenshot unavailable for person scan: ${t.message}")
        }
    }

    private fun detect(shot: Bitmap) {
        val w = shot.width
        val h = shot.height
        try {
            val image = InputImage.fromBitmap(shot, 0)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    val boxes = faces.map { val b = it.boundingBox; Box(b.left, b.top, b.right, b.bottom) }
                    val out = PersonCover.covers(boxes, Box(0, 0, w, h)).map { Rect(it.left, it.top, it.right, it.bottom) }
                    covers = out
                    onCovers(out)
                }
                .addOnFailureListener { VeilLog.w("Face detect failed: ${it.message}") }
                .addOnCompleteListener {
                    busy = false
                    shot.recycle()
                }
        } catch (t: Throwable) {
            busy = false
            shot.recycle()
            VeilLog.w("Face detect error: ${t.message}")
        }
    }

    fun close() {
        try { detector.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val MIN_INTERVAL_MS = 700L
    }
}
