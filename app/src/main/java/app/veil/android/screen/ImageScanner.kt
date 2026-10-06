package app.veil.android.screen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import app.veil.android.VeilLog
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executor

/**
 * The image layer of the screen filter (Stage 4). Takes a screenshot through
 * the accessibility service (Android 11+), crops the on-screen image areas,
 * runs each through an on-device model and reports the ones to cover.
 * Nothing leaves the phone: no screenshot or image is stored or sent.
 *
 * Verdicts are kept per on-screen rectangle until the screen scrolls or the
 * window changes, because the next screenshot shows VEIL's own cover there.
 */
class ImageScanner(private val service: AccessibilityService, private val executor: Executor) {

    private var interpreter: Interpreter? = null
    private var loadFailed = false
    @Volatile private var busy = false
    @Volatile private var lastRunAt = 0L
    private val verdicts = HashMap<String, Boolean>()
    @Volatile var covers: List<Rect> = emptyList()
        private set

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 30

    /** Forget verdicts: the content under each rectangle has changed. */
    @Synchronized
    fun reset() {
        verdicts.clear()
        covers = emptyList()
    }

    /**
     * Classifies any image areas not seen since the last reset. [onDone] runs on
     * the executor with the full set of rectangles to cover, only when it changed.
     */
    fun scan(regions: List<Rect>, strictness: String, onDone: (covers: List<Rect>, newlyCovered: Int) -> Unit) {
        if (!supported || busy || regions.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastRunAt < MIN_INTERVAL_MS) return
        val fresh = synchronized(this) { regions.filter { key(it) !in verdicts } }
        if (fresh.isEmpty()) return
        busy = true
        lastRunAt = now
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = result.hardwareBuffer
                        val shot = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        hw.close()
                        if (shot != null) {
                            val newly = classify(shot, fresh, strictness)
                            shot.recycle()
                            onDone(covers, newly)
                        }
                    } catch (t: Throwable) {
                        VeilLog.w("Image scan failed: ${t.message}")
                    } finally {
                        busy = false
                    }
                }

                override fun onFailure(errorCode: Int) {
                    busy = false // e.g. too soon after the previous screenshot; the next event retries
                }
            })
        } catch (t: Throwable) {
            busy = false
            VeilLog.w("Screenshot unavailable: ${t.message}")
        }
    }

    /** Returns how many of [regions] got covered. */
    private fun classify(shot: Bitmap, regions: List<Rect>, strictness: String): Int {
        val model = model() ?: return 0
        var newly = 0
        val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
        val output = arrayOf(FloatArray(5))
        val pixels = IntArray(SIZE * SIZE)
        val bounds = Rect(0, 0, shot.width, shot.height)
        for (r in regions) {
            val crop = Rect(r)
            if (!crop.intersect(bounds) || crop.width() < MIN_SIDE_PX || crop.height() < MIN_SIDE_PX) {
                synchronized(this) { verdicts[key(r)] = false }
                continue
            }
            val scaled = Bitmap.createScaledBitmap(Bitmap.createBitmap(shot, crop.left, crop.top, crop.width(), crop.height()), SIZE, SIZE, true)
            scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            scaled.recycle()
            input.rewind()
            for (p in pixels) {
                // Same preprocessing as nsfwjs: RGB scaled to 0..1.
                input.putFloat(((p shr 16) and 0xFF) / 255f)
                input.putFloat(((p shr 8) and 0xFF) / 255f)
                input.putFloat((p and 0xFF) / 255f)
            }
            input.rewind()
            model.run(input, output)
            val cover = ImageVerdict.shouldCover(output[0], strictness)
            if (cover) newly++
            synchronized(this) { verdicts[key(r)] = cover }
        }
        synchronized(this) {
            covers = verdicts.filterValues { it }.keys.map { k -> k.split(',').map(String::toInt).let { Rect(it[0], it[1], it[2], it[3]) } }
        }
        return newly
    }

    private fun model(): Interpreter? {
        interpreter?.let { return it }
        if (loadFailed) return null
        return try {
            val opts = Interpreter.Options().setNumThreads(2)
            Interpreter(loadModel(service), opts).also {
                interpreter = it
                VeilLog.i("Image model loaded")
            }
        } catch (t: Throwable) {
            // e.g. no native library for this CPU (only ARM builds are shipped).
            loadFailed = true
            VeilLog.e("Image model could not be loaded; image filtering is off", t)
            null
        }
    }

    fun close() {
        try { interpreter?.close() } catch (_: Throwable) {}
        interpreter = null
    }

    companion object {
        private const val SIZE = 224
        private const val MIN_SIDE_PX = 64
        private const val MIN_INTERVAL_MS = 900L
        private const val MAX_REGIONS = 12
        const val MODEL_ASSET = "models/nsfw_mobilenet_v2.tflite"

        private fun key(r: Rect) = "${r.left},${r.top},${r.right},${r.bottom}"

        private fun loadModel(c: Context): MappedByteBuffer {
            // The asset is stored uncompressed (androidResources.noCompress), so it can be memory-mapped.
            val fd = c.assets.openFd(MODEL_ASSET)
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

        /**
         * Image areas on screen: image views (including images inside web
         * pages) and large text-free leaf views such as video thumbnails,
         * biggest first.
         */
        fun regions(root: AccessibilityNodeInfo, minSidePx: Int): List<Rect> {
            val out = ArrayList<Rect>()
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < 2500) {
                val n = stack.removeLast()
                visited++
                try {
                    val cls = n.className?.toString() ?: ""
                    val leaf = n.childCount == 0
                    val textless = n.text.isNullOrEmpty()
                    if (n.isVisibleToUser && (cls.contains("Image") || (leaf && textless && cls.contains("View")))) {
                        val b = Rect()
                        n.getBoundsInScreen(b)
                        if (b.width() >= minSidePx && b.height() >= minSidePx) out.add(b)
                    }
                    for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
                } catch (_: Throwable) {}
            }
            return out.distinctBy { key(it) }.sortedByDescending { it.width().toLong() * it.height() }.take(MAX_REGIONS)
        }
    }
}
