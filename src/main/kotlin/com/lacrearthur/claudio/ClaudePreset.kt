package com.lacrearthur.claudio

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
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
            val type = object : TypeToken<List<ClaudePreset>>() {}.type
            Gson().fromJson<List<ClaudePreset>>(text, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(presets: List<ClaudePreset>) {
        try {
            file.parentFile.mkdirs()
            file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(presets))
        } catch (_: Exception) {}
    }
}
