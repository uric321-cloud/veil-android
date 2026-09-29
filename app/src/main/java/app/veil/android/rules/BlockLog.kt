package app.veil.android.rules

import android.content.Context
import app.veil.android.VeilLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Recent blocks, newest first, aggregated per host within a short window so an
 * app polling a blocked domain does not flood the list. Persisted to a JSON
 * file with a small delay; shared by the service and the UI (same process).
 */
object BlockLog {
    class Entry(val host: String, val reason: String, val rule: String, var firstAt: Long, var lastAt: Long, var count: Int)

    private const val MAX = 300
    private const val MERGE_WINDOW_MS = 60_000L
    private val entries = ArrayList<Entry>()
    @Volatile private var loaded = false
    @Volatile private var dirty = false
    @Volatile private var lastSave = 0L
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        file = File(context.filesDir, "blocklog.json")
        try {
            val f = file!!
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    entries.add(Entry(o.getString("host"), o.getString("reason"), o.optString("rule", ""),
                        o.getLong("firstAt"), o.getLong("lastAt"), o.getInt("count")))
                }
            }
        } catch (t: Throwable) {
            VeilLog.e("BlockLog load failed", t)
            entries.clear()
        }
        loaded = true
    }

    /** Records a block. Returns true if this is a new entry (not merged into a recent one). */
    @Synchronized
    fun record(host: String, reason: String, rule: String): Boolean {
        val now = System.currentTimeMillis()
        val top = entries.firstOrNull()
        val merged = if (top != null && top.host == host && now - top.lastAt < MERGE_WINDOW_MS) {
            top.lastAt = now; top.count += 1; true
        } else {
            entries.add(0, Entry(host, reason, rule, now, now, 1))
            while (entries.size > MAX) entries.removeAt(entries.size - 1)
            false
        }
        dirty = true
        maybeSave(now)
        return !merged
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.map { Entry(it.host, it.reason, it.rule, it.firstAt, it.lastAt, it.count) }

    @Synchronized
    fun clear() {
        entries.clear()
        dirty = true
        save()
    }

    @Synchronized
    fun flush() { if (dirty) save() }

    private fun maybeSave(now: Long) {
        if (now - lastSave > 10_000) save()
    }

    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(JSONObject().put("host", e.host).put("reason", e.reason).put("rule", e.rule)
                    .put("firstAt", e.firstAt).put("lastAt", e.lastAt).put("count", e.count))
            }
            f.writeText(arr.toString())
            dirty = false
            lastSave = System.currentTimeMillis()
        } catch (t: Throwable) {
            VeilLog.e("BlockLog save failed", t)
        }
    }
}
