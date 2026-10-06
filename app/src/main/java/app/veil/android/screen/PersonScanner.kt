package app.veil.android.screen

import android.graphics.Bitmap
import android.graphics.Rect
import app.veil.android.VeilLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * "Blur every person" layer of the screen filter. Finds faces with ML Kit's
 * on-device face detector (a bundled model — no network, no Google Play Services)
 * and turns each into a head-to-body cover via [PersonCover]. Nothing leaves the
 * phone: no screenshot or face data is stored or sent.
 *
 * It does NOT take its own screenshot. The image scanner already captures one on
 * each scan, and Android rate-limits screen captures to about one per second, so
 * a second capture here would fail on any page with images — exactly the pages
 * that matter. Instead [process] is fed the image scanner's bitmap, so one
 * capture drives both layers.
 */
class PersonScanner(private val onCovers: (List<Rect>) -> Unit) {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.03f) // catch small faces (grid thumbnails, group shots) too
                .build()
        )
    }

    @Volatile private var busy = false

    /** The rectangles to cover from the last scan. */
    @Volatile var covers: List<Rect> = emptyList()
        private set

    /** Forget covers: the screen content changed. */
    fun reset() { covers = emptyList() }

    /**
     * Cover every person in [shot] (a screenshot). Owns [shot] and recycles it
     * when done. Non-reentrant: a frame that arrives while one is in flight is
     * dropped (the next scan picks up the current screen).
     */
    fun process(shot: Bitmap) {
        if (busy) { shot.recycle(); return }
        busy = true
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
}
