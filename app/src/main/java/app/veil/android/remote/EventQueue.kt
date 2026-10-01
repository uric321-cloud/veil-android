package app.veil.android.remote

import android.content.Context
import app.veil.android.VeilLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Things to report to the admin on the next check-in: blocks (merged per host
 * so a polling app doesn't flood the queue), tamper events, and names of sites
 * no list covered (for AI classification). Only filled while paired.
 * Persisted so nothing is lost if the process dies between check-ins.
 */
object EventQueue {
    private const val MAX_EVENTS = 2000
    private const val MAX_UNKNOWN = 300

    private val events = ArrayList<JSONObject>()
    private val pendingBlocks = LinkedHashMap<String, JSONObject>()
    private val unknown = LinkedHashSet<String>()
    private var file: File? = null
    @Volatile private var enabled = false
    @Volatile private var classify = false
    private var dirtySince = 0L

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "remote-queue.json")
        refresh(context)
        try {
            val f = file!!
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val arr = o.optJSONArray("events") ?: JSONArray()
                for (i in 0 until arr.length()) events.add(arr.getJSONObject(i))
            }
        } catch (t: Throwable) {
            VeilLog.w("Remote queue could not be read: ${t.message}")
        }
    }

    /** Re-reads whether we are paired and whether AI classification is on. */
    fun refresh(context: Context) {
        enabled = RemoteStore.get(context).isPaired
        classify = enabled && app.veil.android.rules.RuleStore.get(context).aiClassification
        if (!enabled) synchronized(this) { events.clear(); pendingBlocks.clear(); unknown.clear() }
    }

    @Synchronized
    fun block(host: String, reason: String, rule: String) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val e = pendingBlocks[host]
        if (e != null) {
            e.put("count", e.optInt("count", 1) + 1).put("lastAt", now)
        } else {
            if (pendingBlocks.size >= 500) flushBlocksLocked()
            pendingBlocks[host] = JSONObject().put("type", "block").put("at", now).put("host", host).put("reason", reason).put("rule", rule).put("count", 1)
        }
        markDirty(now)
    }

    @Synchronized
    fun tamper(rule: String, detail: String = "") {
        if (!enabled) return
        add(JSONObject().put("type", "tamper").put("at", System.currentTimeMillis()).put("rule", rule).put("detail", detail))
        save()
    }

    @Synchronized
    fun text(count: Long) {
        if (!enabled || count <= 0) return
        add(JSONObject().put("type", "text").put("at", System.currentTimeMillis()).put("count", count))
    }

    /** A lookup no rule matched. Only the site's main name is kept (shop.example.co.uk -> example.co.uk). */
    @Synchronized
    fun unknownSite(host: String) {
        if (!classify || unknown.size >= MAX_UNKNOWN) return
        unknown.add(siteOf(host))
    }

    /** Takes everything waiting. Call [restore] with the same lists if the upload fails. */
    @Synchronized
    fun drain(): Pair<List<JSONObject>, List<String>> {
        flushBlocksLocked()
        val e = ArrayList(events)
        val u = ArrayList(unknown)
        events.clear()
        unknown.clear()
        save()
        return e to u
    }

    @Synchronized
    fun restore(e: List<JSONObject>, u: List<String>) {
        events.addAll(0, e)
        while (events.size > MAX_EVENTS) events.removeAt(events.size - 1)
        for (x in u) if (unknown.size < MAX_UNKNOWN) unknown.add(x)
        save()
    }

    private fun flushBlocksLocked() {
        for (b in pendingBlocks.values) add(b)
        pendingBlocks.clear()
    }

    private fun add(o: JSONObject) {
        events.add(o)
        while (events.size > MAX_EVENTS) events.removeAt(0)
    }

    private fun markDirty(now: Long) {
        if (dirtySince == 0L) dirtySince = now
        if (now - dirtySince > 30_000) { flushBlocksLocked(); save() }
    }

    private fun save() {
        dirtySince = 0L
        val f = file ?: return
        try {
            val arr = JSONArray()
            for (e in events) arr.put(e)
            f.writeText(JSONObject().put("events", arr).toString())
        } catch (t: Throwable) {
            VeilLog.w("Remote queue could not be saved: ${t.message}")
        }
    }

    private val twoPartSuffixes = setOf("co", "com", "org", "net", "gov", "ac", "edu", "ne", "or", "go")

    fun siteOf(host: String): String {
        val labels = host.trim('.').split('.')
        if (labels.size <= 2) return host
        val keep = if (labels[labels.size - 1].length == 2 && labels[labels.size - 2] in twoPartSuffixes) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }
}
