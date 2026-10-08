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

    /** Face boxes seen since the last reset. Covers are STICKY: once a person is
     *  covered, our own cover bar hides that face from the next screenshot, so the
     *  detector would see "no face" and uncover it, causing a cover/uncover flicker.
     *  Keeping the boxes until the screen scrolls or changes stops that loop. */
    private val seen = ArrayList<Box>()
    @Volatile private var lastScreen = Box(0, 0, 0, 0)

    /** Video surface rects on screen this frame (from accessibility nodes), set by
     *  the service before each scan. A face inside one covers the whole surface. */
    @Volatile var videoSurfaces: List<Rect> = emptyList()
    private val flaggedVideos = ArrayList<Box>()

    /** The rectangles to cover from the scans since the last reset. */
    @Volatile var covers: List<Rect> = emptyList()
        private set

    /** Forget covers: the screen content changed (scroll / new window). */
    fun reset() {
        synchronized(seen) { seen.clear(); flaggedVideos.clear() }
        covers = emptyList()
    }

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
                    val faceBoxes = faces.map { val b = it.boundingBox; Box(b.left, b.top, b.right, b.bottom) }
                    val surfaces = videoSurfaces.map { Box(it.left, it.top, it.right, it.bottom) }
                    val out = synchronized(seen) {
                        lastScreen = Box(0, 0, w, h)
                        // Still images: accumulate faces (deduped); a frame with no
                        // faces shrinks nothing, so a covered person stays covered.
                        for (box in faceBoxes) if (seen.none { near(it, box) }) seen.add(box)
                        val people = PersonCover.covers(seen, lastScreen)
                        // Moving video: a face inside a video surface covers the whole
                        // surface, sticky until the surface leaves the screen.
                        val videos = VideoCover.cover(faceBoxes, surfaces, flaggedVideos)
                        (people + videos).map { Rect(it.left, it.top, it.right, it.bottom) }
                    }
                    if (out != covers) {
                        covers = out
                        onCovers(out)
                    }
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

    /** Two face boxes are "the same face" if their centres are within half a face
     *  width/height of each other — so frame-to-frame jitter doesn't pile up. */
    private fun near(a: Box, b: Box): Boolean {
        val dx = kotlin.math.abs(a.centerX - b.centerX)
        val dy = kotlin.math.abs((a.top + a.bottom) / 2 - (b.top + b.bottom) / 2)
        return dx <= (a.width + b.width) / 4 && dy <= (a.height + b.height) / 4
    }

    fun close() {
        try { detector.close() } catch (_: Throwable) {}
    }
}
