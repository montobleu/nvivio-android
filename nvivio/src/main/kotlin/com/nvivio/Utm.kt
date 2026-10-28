package com.nvivio

import java.net.URLDecoder

private val UTM_KEYS = setOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content")

/**
 * The utm_* parameters in a deep link's query ("myshop://product/7?utm_source=newsletter"),
 * decoded; the first of each counts. Parsed from the link's text rather than with
 * android.net.Uri, which is only a stub in JVM unit tests.
 */
internal fun utmOf(link: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val query = link.substringBefore('#').substringAfter('?', "")
    if (query.isEmpty()) return out
    for (pair in query.split('&')) {
        val key = decode(pair.substringBefore('=')) ?: continue
        val value = decode(pair.substringAfter('=', ""))?.trim()
        if (key in UTM_KEYS && key !in out && !value.isNullOrEmpty()) out[key] = value
    }
    return out
}

private fun decode(s: String): String? = try {
    URLDecoder.decode(s, "UTF-8")
} catch (e: IllegalArgumentException) {
    null
}
