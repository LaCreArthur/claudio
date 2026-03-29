package com.lacrearthur.claudio

import java.io.File

internal data class ClaudePreset(val name: String, val systemPrompt: String, val model: String = "")

internal val DEFAULT_PRESETS = listOf(
    ClaudePreset("Backend Agent", "You are a backend engineering specialist. Focus on server-side code, APIs, databases, and performance. Be direct and concise."),
    ClaudePreset("Review Agent", "You are a code reviewer. Analyze code for bugs, security issues, performance problems, and style. Give specific, actionable feedback."),
    ClaudePreset("Test Agent", "You are a testing specialist. Write unit tests, integration tests, and suggest edge cases. Prefer practical coverage over 100% coverage."),
)

internal object PresetStore {
    private val file = File(System.getProperty("user.home"), ".claudio/presets.json")

    fun load(): List<ClaudePreset> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText().trim()
            if (text.isEmpty() || text == "[]") return emptyList()
            // Parse: [{"name":"...","systemPrompt":"..."},...]
            val results = mutableListOf<ClaudePreset>()
            var remaining = text.trimStart('[').trimEnd(']').trim()
            while (remaining.isNotEmpty()) {
                val objEnd = JsonUtils.findClosingBrace(remaining, 0)
                if (objEnd < 0) break
                val obj = remaining.substring(0, objEnd + 1)
                remaining = remaining.substring(objEnd + 1).trimStart(',', ' ', '\n', '\r', '\t')
                val name = JsonUtils.extractString(obj, "name") ?: continue
                val prompt = JsonUtils.extractString(obj, "systemPrompt") ?: continue
                val model = JsonUtils.extractString(obj, "model") ?: ""
                results.add(ClaudePreset(name, prompt, model))
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(presets: List<ClaudePreset>) {
        file.parentFile.mkdirs()
        val sb = StringBuilder("[")
        presets.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("{\"name\":\"${escapeJson(p.name)}\",\"systemPrompt\":\"${escapeJson(p.systemPrompt)}\",\"model\":\"${escapeJson(p.model)}\"}")
        }
        sb.append("]")
        file.writeText(sb.toString())
    }

    private fun escapeJson(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

}
