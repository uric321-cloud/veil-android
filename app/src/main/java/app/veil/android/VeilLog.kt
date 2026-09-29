package app.veil.android

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory ring-buffer log that also mirrors to logcat. The "Share diagnostics"
 * button exports it, so a tester can report what happened without adb.
 */
object VeilLog {
    private const val TAG = "VEIL"
    private const val CAPACITY = 600
    private val lines = ArrayDeque<String>(CAPACITY)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun i(msg: String) = add("I", msg).also { Log.i(TAG, msg) }

    @Synchronized
    fun w(msg: String) = add("W", msg).also { Log.w(TAG, msg) }

    @Synchronized
    fun e(msg: String, t: Throwable? = null) {
        add("E", if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)
        Log.e(TAG, msg, t)
    }

    @Synchronized
    fun d(msg: String) = add("D", msg).also { Log.d(TAG, msg) }

    private fun add(level: String, msg: String) {
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast("${fmt.format(Date())} $level $msg")
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
