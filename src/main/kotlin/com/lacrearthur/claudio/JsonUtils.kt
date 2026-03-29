package com.lacrearthur.claudio

/**
 * Lightweight JSON extraction for hook payloads, session JSONL, and preset files.
 * No external library dependency - handles the subset of JSON this plugin encounters.
 */
internal object JsonUtils {

    /** Extract a string value for the given key, with proper JSON unescape. */
    fun extractString(json: String, key: String): String? {
        val marker = "\"$key\":"
        val idx = json.indexOf(marker)
        if (idx < 0) return null
        var i = idx + marker.length
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        i++ // skip opening quote
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (json[i + 1]) {
                    '"'  -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n'  -> sb.append('\n')
                    'r'  -> sb.append('\r')
                    't'  -> sb.append('\t')
                    '/'  -> sb.append('/')
                    else -> sb.append(json[i + 1])
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString().ifEmpty { null }
    }

    /** Extract the JSON object for the given key as a raw string. Handles nested braces and strings. */
    fun extractObject(json: String, key: String): String {
        val keyIdx = json.indexOf("\"$key\"")
        if (keyIdx < 0) return "{}"
        var i = keyIdx + key.length + 2
        while (i < json.length && json[i] != '{') i++
        if (i >= json.length) return "{}"
        val end = findClosingBrace(json, i)
        return if (end < 0) "{}" else json.substring(i, end + 1)
    }

    /** Find the index of the closing brace matching the opening brace at [start]. Handles strings. */
    fun findClosingBrace(json: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var escape = false
        for (i in start until json.length) {
            val c = json[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inStr) { escape = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (inStr) continue
            if (c == '{') depth++
            if (c == '}') { depth--; if (depth == 0) return i }
        }
        return -1
    }
}

/** Convenience wrapper for chained lookups on a JSON string. */
internal class JsonObj(private val raw: String) {
    fun optString(key: String): String? = JsonUtils.extractString(raw, key)

    fun optObj(key: String): JsonObj? {
        val obj = JsonUtils.extractObject(raw, key)
        return if (obj == "{}") null else JsonObj(obj)
    }
}
