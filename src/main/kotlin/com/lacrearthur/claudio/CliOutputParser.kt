package com.lacrearthur.claudio

import com.intellij.openapi.diagnostic.Logger

data class ParsedQuestion(
    val title: String,
    val body: String,
    val options: List<ParsedOption>,
)

data class ParsedOption(
    val number: Int,
    val label: String,
    val description: String = "",
    val isFreeText: Boolean = false,
)

private enum class CaptureMode { NONE, QUESTION }

// Owns only AskUserQuestion detection (☐ prompts).
// Permission handling is owned by PermissionRequest hook in HookServer.

class CliOutputParser {
    private val log = Logger.getInstance(CliOutputParser::class.java)
    private val buffer = StringBuilder()
    private var captureMode = CaptureMode.NONE
    private var captureStartTime = 0L

    var onQuestion: ((ParsedQuestion) -> Unit)? = null
    var onPermissionMode: ((String) -> Unit)? = null
    var onCostLine: ((cost: Double, tokens: Long) -> Unit)? = null

    private val ansiRegex = Regex("""\u001b(?:\[[0-9;?]*[a-zA-Z]|\][^\u0007]*\u0007|[()][0-9A-B]|[=>])""")

    fun feed(rawText: String) {
        val text = ansiRegex.replace(rawText, "")

        // Cost line: "Cost: $0.0234 · Input: 1,234 tokens · Output: 456 tokens · Cache read: 789 tokens"
        if (text.contains("Cost:") && text.contains("$")) {
            val costMatch = Regex("""Cost:\s*\$(\d+\.\d+)""").find(text)
            if (costMatch != null) {
                val cost = costMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                val inputTok = Regex("""Input:\s*([\d,]+)\s*tokens?""").find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() ?: 0L
                val outputTok = Regex("""Output:\s*([\d,]+)\s*tokens?""").find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() ?: 0L
                val cacheTok = Regex("""Cache[^:]*:\s*([\d,]+)\s*tokens?""").findAll(text).sumOf { it.groupValues[1].replace(",", "").toLongOrNull() ?: 0L }
                if (cost > 0) onCostLine?.invoke(cost, inputTok + outputTok + cacheTok)
            }
        }

        // Permission mode badge: "⏵⏵ accept edits on (shift+tab to cycle)" or "? for shortcuts"
        val modeLine = text.lines().firstOrNull { it.contains("shift+tab to cycle") || it.trim() == "? for shortcuts" }
        if (modeLine != null) {
            val mode = if (modeLine.contains("shift+tab to cycle"))
                modeLine.trim().replace(Regex("^[^a-zA-Z'\"]+"), "").substringBefore(" on (").trim()
            else
                "default"
            if (mode.isNotEmpty()) onPermissionMode?.invoke(mode)
        }

        // AskUserQuestion: start capture on ☐
        if (captureMode == CaptureMode.NONE && text.contains("☐")) {
            val trigger = text.lines().firstOrNull { it.contains("☐") }?.trim()?.take(80) ?: ""
            log.warn("[PARSER] QUESTION capture start: '$trigger'")
            captureMode = CaptureMode.QUESTION
            buffer.clear()
            captureStartTime = System.currentTimeMillis()
        }

        if (captureMode == CaptureMode.NONE) return

        buffer.append(text)

        if (System.currentTimeMillis() - captureStartTime > 30_000) {
            log.warn("[PARSER] capture timed out")
            captureMode = CaptureMode.NONE
            buffer.clear()
            return
        }

        // End: separator line signals question is fully rendered
        if (captureMode == CaptureMode.QUESTION && buffer.contains("────")) {
            captureMode = CaptureMode.NONE
            try {
                parseQuestion(buffer.toString())?.let { question ->
                    log.warn("[PARSER] QUESTION firing: '${question.title}' opts=${question.options.mapIndexed { i, o -> "$i:${o.label}${if (o.isFreeText) "(FT)" else ""}" }}")
                    onQuestion?.invoke(question)
                }
            } catch (e: Exception) {
                log.warn("[PARSER] failed to parse question: ${e.message}")
            }
            buffer.clear()
        }
    }

    private fun parseQuestion(raw: String): ParsedQuestion? {
        val lines = raw.lines()
        val titleLine = lines.firstOrNull { it.contains("☐") } ?: return null
        val title = titleLine.substringAfter("☐").trim()

        val optRegex = Regex("""[❯\s]*(\d+)\.\s+(.+)""")
        val options = mutableListOf<ParsedOption>()
        val bodyLines = mutableListOf<String>()
        var foundFirstOption = false
        var lastOptionIdx = -1

        for ((i, line) in lines.withIndex()) {
            if (line.contains("☐") || line.contains("────")) continue
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val match = optRegex.matchEntire(trimmed)
            if (match != null) {
                foundFirstOption = true
                val num = match.groupValues[1].toInt()
                val label = match.groupValues[2]
                val isFreeText = label.trim() == "__other__"
                        || label.contains("Type something", ignoreCase = true)
                        || label.contains("type your", ignoreCase = true)
                options.add(ParsedOption(num, label, isFreeText = isFreeText))
                lastOptionIdx = i
            } else if (!foundFirstOption) {
                bodyLines.add(trimmed)
            } else if (lastOptionIdx >= 0 && options.isNotEmpty()) {
                val last = options.last()
                if (last.description.isEmpty()) {
                    options[options.lastIndex] = last.copy(description = trimmed)
                }
            }
        }

        log.warn("[PARSER] AskUserQuestion: '$title' with ${options.size} options (freeText=${options.isEmpty()})")
        return ParsedQuestion(title, bodyLines.joinToString("\n"), options)
    }
}
