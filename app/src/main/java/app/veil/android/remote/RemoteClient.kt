package app.veil.android.remote

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal JSON-over-HTTPS client for the admin backend. Call off the main thread. */
class RemoteClient(private val server: String, private val token: String?) {

    class HttpException(val status: Int, message: String) : IOException(message)

    fun post(path: String, body: JSONObject): JSONObject = request("POST", path, body)

    fun get(path: String): JSONObject = request("GET", path, null)

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val conn = URL(server.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("accept", "application/json")
            if (token != null) conn.setRequestProperty("authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (_: Throwable) { JSONObject() }
            if (status !in 200..299) throw HttpException(status, json.optString("error").ifEmpty { "Server answered $status" })
            return json
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /**
         * Accepts "veil-admin.netlify.app" or a full URL; requires https except
         * for local test servers (emulator host, localhost).
         */
        fun normalizeServer(input: String): String? {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            val url = try { URL(s) } catch (_: Throwable) { return null }
            val local = url.host == "10.0.2.2" || url.host == "localhost" || url.host == "127.0.0.1"
            if (url.protocol != "https" && !local) return null
            if (url.host.isNullOrEmpty()) return null
            return "${url.protocol}://${url.authority}"
        }
    }
}
