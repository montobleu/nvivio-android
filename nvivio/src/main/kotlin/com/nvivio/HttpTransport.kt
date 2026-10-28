package com.nvivio

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal const val VERSION = "1.0.0"

/** Starts with Nvivio-Android/: the server takes Android's own clients (okhttp/…, Dalvik/…) for bots. */
private const val USER_AGENT = "Nvivio-Android/$VERSION"
private const val TIMEOUT_MS = 10_000

/** POSTs the JSON with HttpURLConnection, without HTTP cache, on the SDK's own thread. */
internal class HttpTransport(private val endpoint: URL) : Transport {
    override fun post(body: String): Answer {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val c = endpoint.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.useCaches = false
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val status = c.responseCode
            if (status !in SETUP_ERRORS) return Answer(status)
            // {"error": "...", "fix": "..."}: what is wrong and how to set it right.
            val json = c.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }
            val o = json?.let { runCatching { JSONObject(it) }.getOrNull() }
            return Answer(status, o?.optString("error")?.ifEmpty { null }, o?.optString("fix")?.ifEmpty { null })
        } finally {
            c.disconnect()
        }
    }
}
