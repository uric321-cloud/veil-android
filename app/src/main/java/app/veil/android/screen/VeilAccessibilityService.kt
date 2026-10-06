package app.veil.android.screen

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import app.veil.android.VeilLog
import app.veil.android.apps.AppControl
import app.veil.android.apps.BlockedAppActivity
import app.veil.android.rules.RuleStore
import app.veil.android.rules.TextRules

/**
 * The text layer of the screen engine. Reads on-screen text on change events,
 * matches it against the rules, and drives the overlay to cover flagged words.
 * Event-driven and debounced; scanning runs on a background thread, the overlay
 * is updated on the main thread. See docs/tickets/stage-1-text-redaction.md.
 */
class VeilAccessibilityService : AccessibilityService() {

    private lateinit var store: RuleStore
    private lateinit var textRules: TextRules
    private lateinit var overlay: OverlayController
    @Volatile private var engine: TextRuleEngine? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    @Volatile private var currentPackage: String? = null
    private lateinit var images: ImageScanner
    /** Text and in-app covers from the last scan; image covers are added on top as they arrive. */
    @Volatile private var baseCovers: List<Cover> = emptyList()
    @Volatile private var screenOn = true

    private val scanRunnable = Runnable { doScan() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in RuleStore.SCREEN_RULE_KEYS) {
            bgHandler.post { rebuildEngine() }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; clearOverlay() }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = RuleStore.get(this)
        textRules = TextRules(this)
        overlay = OverlayController(this)
        bgThread = HandlerThread("veil-screen").apply { start() }
        bgHandler = Handler(bgThread.looper)
        images = ImageScanner(this) { bgHandler.post(it) }
        screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        bgHandler.post { rebuildEngine() }
        store.registerListener(prefsListener)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        VeilLog.i("Accessibility service connected")
    }

    private fun rebuildEngine() {
        try {
            engine = textRules.engine(store)
            VeilLog.i("Text engine rebuilt (tier=${store.textTier})")
        } catch (t: Throwable) {
            VeilLog.e("Text engine rebuild failed", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // What's under each image rectangle changed; look again.
            if (this::images.isInitialized) images.reset()
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let {
                currentPackage = it.toString()
                checkBlockedApp(it.toString())
            }
        }
        // Debounce: collapse a burst of events into a single scan.
        bgHandler.removeCallbacks(scanRunnable)
        bgHandler.postDelayed(scanRunnable, DEBOUNCE_MS)
    }

    @Volatile private var lastBlockedAt = 0L

    /**
     * App control without Device Owner: a blocked app is sent back to the home
     * screen as soon as it comes to the front. (With Device Owner it can't start at all.)
     */
    private fun checkBlockedApp(pkg: String) {
        if (pkg == packageName || !AppControl.isBlocked(this, pkg)) return
        val now = System.currentTimeMillis()
        if (now - lastBlockedAt < 800) return
        lastBlockedAt = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        VeilLog.i("Blocked app closed: $pkg")
        mainHandler.postDelayed({ BlockedAppActivity.show(this, pkg) }, 250)
    }

    @Volatile private var lastLeaveAt = 0L

    private fun doScan() {
        if (!screenOn) { clearOverlay(); return }
        // Nothing to do for this app: don't even read the screen (saves battery).
        val cp = currentPackage
        val imagesWanted = store.screenProtectionWanted && store.imageFilter && images.supported
        if (!(store.screenProtectionWanted && store.textEnabled) && !imagesWanted && (cp == null || InAppRules.forApp(this, cp).isEmpty())) { clearOverlay(); return }
        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: run { clearOverlay(); return }
        val pkg = root.packageName?.toString() ?: currentPackage
        val covers = ArrayList<Cover>()

        // In-app blocking runs whether or not the word filter is on: it's the admin's rule.
        if (pkg != null && pkg != packageName) {
            val rules = InAppRules.forApp(this, pkg)
            if (rules.isNotEmpty()) {
                val r = InAppRules.evaluate(root, rules)
                for (rect in r.covers) covers.add(Cover(rect, TextAction.BAR))
                val now = System.currentTimeMillis()
                if (r.leave && now - lastLeaveAt > 1500) {
                    lastLeaveAt = now
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    mainHandler.post { android.widget.Toast.makeText(this, "This part of the app is blocked", android.widget.Toast.LENGTH_SHORT).show() }
                }
            }
        }

        val safe = pkg != null && pkg in store.safeListApps
        val textOn = store.screenProtectionWanted && store.textEnabled && !safe
        val eng = engine
        if (imagesWanted && !safe) scanImages(root)
        if (!textOn || eng == null) {
            publish(covers)
            return
        }

        val texts = ScreenTextScanner.scan(root)
        var count = 0
        for (t in texts) {
            if (t.isPassword) continue // never scan, cover, or log password fields
            val reds = eng.scan(t.text)
            if (reds.isEmpty()) continue
            for (r in reds) {
                count++
                if (r.action == TextAction.LOG || r.action == TextAction.IGNORE) continue
                covers.add(Cover(coverRect(t, r), r.action))
            }
        }
        if (count > 0) store.countTextCovered(count)
        publish(covers)
    }

    /** Shows text/in-app covers plus the image covers currently known. */
    private fun publish(base: List<Cover>) {
        baseCovers = dedupe(base)
        val all = baseCovers + images.covers.map { Cover(it, TextAction.BAR) }
        mainHandler.post { overlay.update(all) }
    }

    private fun scanImages(root: android.view.accessibility.AccessibilityNodeInfo) {
        val failClosed = store.imageStrictness == "max"
        // Max mode covers first and reveals only what the model clears, so it
        // also looks at smaller images and more of them per screen.
        val dp = resources.displayMetrics.density
        val minSide = (dp * (if (failClosed) 40 else 72)).toInt()
        val maxRegions = if (failClosed) 20 else 12
        val regions = ImageScanner.regions(root, minSide, maxRegions)
        images.scan(regions, store.imageStrictness, failClosed) { imageRects, newly ->
            if (newly > 0) store.countImagesCovered(newly)
            val all = baseCovers + imageRects.map { Cover(it, TextAction.BAR) }
            mainHandler.post { overlay.update(all) }
        }
    }

    /** Approximate the flagged word's rectangle inside a single-line node; fall back to the whole node. */
    private fun coverRect(t: ScannedText, r: Redaction): Rect {
        val b = t.bounds
        val len = t.text.length
        if (len <= 0 || t.text.contains('\n') || len > 80) return Rect(b)
        val x1 = b.left + (b.width().toLong() * r.start / len).toInt()
        val x2 = b.left + (b.width().toLong() * r.end / len).toInt()
        val left = x1.coerceIn(b.left, b.right)
        val right = x2.coerceIn(left, b.right)
        return Rect(left, b.top, right, b.bottom)
    }

    private fun dedupe(covers: List<Cover>): List<Cover> {
        if (covers.size < 2) return covers
        val seen = HashSet<String>(covers.size * 2)
        val out = ArrayList<Cover>(covers.size)
        for (c in covers) {
            val key = "${c.rect.left},${c.rect.top},${c.rect.right},${c.rect.bottom},${c.action}"
            if (seen.add(key)) out.add(c)
        }
        return out
    }

    private fun clearOverlay() {
        mainHandler.post { overlay.clear() }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private var torndown = false
    private fun teardown() {
        if (torndown) return
        torndown = true
        try { store.unregisterListener(prefsListener) } catch (_: Throwable) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
        mainHandler.post { overlay.hide() }
        if (this::images.isInitialized) bgHandler.post { images.close() }
        if (this::bgThread.isInitialized) bgThread.quitSafely()
        VeilLog.i("Accessibility service torn down")
    }

    companion object {
        private const val DEBOUNCE_MS = 150L
    }
}
