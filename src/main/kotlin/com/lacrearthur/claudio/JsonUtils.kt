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

    /** Extract an integer value for the given key. */
    fun extractInt(json: String, key: String): Int? {
        val marker = "\"$key\":"
        val idx = json.indexOf(marker)
        if (idx < 0) return null
        var i = idx + marker.length
        while (i < json.length && json[i].isWhitespace()) i++
        val start = i
        while (i < json.length && (json[i].isDigit() || json[i] == '-')) i++
        if (i == start) return null
        return json.substring(start, i).toIntOrNull()
    }

    /** Build a JSON object from key-value pairs. Values are serialized: String gets quoted, others use toString(). */
    fun buildJsonObject(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in pairs) {
            if (v == null) continue
            if (!first) sb.append(",")
            first = false
            sb.append("\"$k\":")
            when (v) {
                is String -> sb.append("\"${escapeJsonString(v)}\"")
                is RawJson -> sb.append(v.json)
                is Boolean, is Number -> sb.append(v)
                else -> sb.append("\"${escapeJsonString(v.toString())}\"")
            }
        }
        sb.append("}")
        return sb.toString()
    }

    /** Build a JSON array from pre-serialized JSON strings. */
    fun buildJsonArray(items: List<String>): String =
        "[${items.joinToString(",")}]"

    /** Escape special characters for JSON string values. */
    private fun escapeJsonString(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    /** Wrapper to embed raw JSON without quoting. */
    class RawJson(val json: String)

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
