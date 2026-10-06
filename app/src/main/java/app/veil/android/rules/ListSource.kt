package app.veil.android.rules

import android.content.Context
import app.veil.android.VeilLog
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads the bundled domain lists from assets, or the updated copy in the app's
 * files directory if "Update lists" has been used. Sets are cached in memory.
 */
class ListSource(private val context: Context) {

    @Volatile private var adult: DomainSet? = null
    @Volatile private var adultLoadedFrom: String = ""
    @Volatile private var bypass: DomainSet? = null
    @Volatile private var aiBlock: DomainSet? = null
    @Volatile private var aiBlockStamp: Long = -1

    private fun updatedFile(): File = File(context.filesDir, "adult-updated.txt")

    fun adultSet(): DomainSet {
        val f = updatedFile()
        val source = if (f.exists() && f.length() > 100_000) "file:" + f.lastModified() else "asset"
        adult?.let { if (adultLoadedFrom == source) return it }
        synchronized(this) {
            adult?.let { if (adultLoadedFrom == source) return it }
            val t0 = System.currentTimeMillis()
            val stream: InputStream = if (source.startsWith("file:")) f.inputStream() else context.assets.open("lists/adult.txt")
            val set = stream.use { DomainSet(readDomains(it)) }
            adult = set
            adultLoadedFrom = source
            VeilLog.i("Adult list loaded: ${set.size} domains from ${if (source == "asset") "bundled asset" else "updated file"} in ${System.currentTimeMillis() - t0} ms")
            return set
        }
    }

    fun bypassSet(): DomainSet {
        bypass?.let { return it }
        synchronized(this) {
            bypass?.let { return it }
            val set = context.assets.open("lists/bypass.txt").use { DomainSet(readDomains(it)) }
            bypass = set
            return set
        }
    }

    fun adultDomainCount(): Int = adultSet().size

    private fun aiBlockFile(): File = File(context.filesDir, "ai-blocklist.txt")

    /** The admin backend's shared AI blocklist, as last downloaded. Reloaded when the file changes. */
    fun aiBlockSet(): DomainSet {
        val f = aiBlockFile()
        val stamp = if (f.exists()) f.lastModified() else 0L
        aiBlock?.let { if (aiBlockStamp == stamp) return it }
        synchronized(this) {
            aiBlock?.let { if (aiBlockStamp == stamp) return it }
            val set = if (f.exists()) f.inputStream().use { DomainSet(readDomains(it)) } else DomainSet(emptyList())
            aiBlock = set
            aiBlockStamp = stamp
            return set
        }
    }

    fun saveAiBlocklist(domains: List<String>) = writeAtomically(aiBlockFile(), domains)

    private fun aiAllowFile(): File = File(context.filesDir, "ai-allowlist.txt")
    @Volatile private var aiAllow: DomainSet? = null
    @Volatile private var aiAllowStamp: Long = -1

    /** The AI's shared list of clearly safe sites, for allowed-sites-only mode. */
    fun aiAllowSet(): DomainSet {
        val f = aiAllowFile()
        val stamp = if (f.exists()) f.lastModified() else 0L
        aiAllow?.let { if (aiAllowStamp == stamp) return it }
        synchronized(this) {
            val set = if (f.exists()) f.inputStream().use { DomainSet(readDomains(it)) } else DomainSet(emptyList())
            aiAllow = set
            aiAllowStamp = stamp
            return set
        }
    }

    fun saveAiAllowlist(domains: List<String>) = writeAtomically(aiAllowFile(), domains)

    @Volatile private var essentials: DomainSet? = null

    fun essentialsSet(): DomainSet {
        essentials?.let { return it }
        synchronized(this) {
            essentials?.let { return it }
            val set = context.assets.open("lists/essentials.txt").use { DomainSet(readDomains(it)) }
            essentials = set
            return set
        }
    }

    private fun writeAtomically(target: File, domains: List<String>) {
        val tmp = File(target.path + ".tmp")
        tmp.writeText(domains.joinToString("\n"))
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    private fun readDomains(stream: InputStream): List<String> {
        val out = ArrayList<String>(50_000)
        stream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                out.add(line)
            }
        }
        return out
    }

    /**
     * Downloads the two public adult-domain lists this app is built from, merges
     * them, and stores the result. Returns the new domain count, or throws.
     * Must be called off the main thread.
     */
    fun updateFromInternet(): Int {
        val merged = HashSet<String>(90_000)
        for (url in SOURCES) {
            val text = fetch(url)
            var n = 0
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                if (parts.size < 2) continue
                if (parts[0] != "0.0.0.0" && parts[0] != "127.0.0.1") continue
                for (i in 1 until parts.size) {
                    val d = parts[i]
                    if (d.startsWith("#")) break
                    val h = d.lowercase().trim('.')
                    if (h == "localhost" || h == "localhost.localdomain" || h == "broadcasthost" || !h.contains('.')) continue
                    if (!h.all { it.isLetterOrDigit() || it == '.' || it == '-' }) continue
                    merged.add(h); n++
                }
            }
            VeilLog.i("List update: $n entries from $url")
        }
        if (merged.size < 10_000) throw IllegalStateException("Downloaded list looks incomplete (${merged.size} entries)")
        // Collapse entries whose parent is also present; the matcher checks parents anyway.
        val collapsed = merged.filter { d ->
            var h = d
            var keep = true
            while (true) {
                val dot = h.indexOf('.')
                if (dot < 0) break
                h = h.substring(dot + 1)
                if (!h.contains('.')) break
                if (merged.contains(h)) { keep = false; break }
            }
            keep
        }.sorted()
        val tmp = File(context.filesDir, "adult-updated.tmp")
        tmp.bufferedWriter().use { w -> for (d in collapsed) { w.write(d); w.newLine() } }
        if (!tmp.renameTo(updatedFile())) throw IllegalStateException("Could not save the updated list")
        adult = null
        return collapsed.size
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "VEIL/0.1 (Android)")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode} from $url")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        val SOURCES = listOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
            "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/pornography-hosts"
        )
    }
}
