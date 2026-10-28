package com.nvivio

/**
 * Writes the JSON the SDK sends: maps (members that are null are left out), lists, strings,
 * numbers and booleans. Hand-made so the SDK has no dependencies; org.json would do on a
 * phone, but is only a stub in JVM unit tests.
 */
internal object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(value)
            is Double -> out.append(if (value.isFinite()) value.toString() else "null")
            is Float -> out.append(if (value.isFinite()) value.toString() else "null")
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (v == null) continue
                    if (!first) out.append(',')
                    first = false
                    string(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            else -> string(out, value.toString())
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
