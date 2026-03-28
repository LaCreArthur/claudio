package com.lacrearthur.claudio

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.*

// ── Data ────────────────────────────────────────────────────────────────────

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

// Used by HookServer for the approval dialog
data class PermissionRequest(
    val action: String,
    val detail: String,
)

private enum class CaptureMode { NONE, QUESTION }

// ── Parser ───────────────────────────────────────────────────────────────────
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
                val isFreeText = label.contains("Type something", ignoreCase = true)
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

// ── AskUserQuestion Dialog ───────────────────────────────────────────────────

class AskUserQuestionDialog(
    project: Project,
    private val question: ParsedQuestion,
) : DialogWrapper(project) {

    private var selectedIndex = 0
    private val freeTextField = JTextField(30)

    init {
        title = "Claude: ${question.title}"
        setOKButtonText("Answer")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        if (question.body.isNotBlank()) {
            val bodyLabel = JLabel("<html><p style='width:350px'>${question.body.replace("\n", "<br>")}</p></html>")
            bodyLabel.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(bodyLabel)
            panel.add(Box.createVerticalStrut(12))
        }

        val group = ButtonGroup()
        question.options.forEachIndexed { idx, opt ->
            val radio = JRadioButton(opt.label).apply {
                isSelected = idx == 0
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { selectedIndex = idx }
            }
            group.add(radio)
            panel.add(radio)

            if (opt.description.isNotBlank()) {
                val desc = JLabel(opt.description).apply {
                    foreground = UIManager.getColor("Component.infoForeground")
                    border = BorderFactory.createEmptyBorder(0, 24, 0, 0)
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                panel.add(desc)
            }

            if (opt.isFreeText) {
                val textPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    border = BorderFactory.createEmptyBorder(2, 24, 4, 0)
                    alignmentX = Component.LEFT_ALIGNMENT
                    add(freeTextField)
                }
                panel.add(textPanel)
                freeTextField.addFocusListener(object : FocusAdapter() {
                    override fun focusGained(e: FocusEvent?) {
                        radio.isSelected = true
                        selectedIndex = idx
                    }
                })
            }
            panel.add(Box.createVerticalStrut(4))
        }

        panel.preferredSize = Dimension(420, panel.preferredSize.height)
        return panel
    }

    fun getSelectedOptionIndex(): Int = selectedIndex
    fun getFreeText(): String = freeTextField.text.trim()
    fun isFreeTextSelected(): Boolean = question.options.getOrNull(selectedIndex)?.isFreeText == true
}

// ── Permission Dialog (used by HookServer) ───────────────────────────────────

enum class PermissionChoice { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

class PermissionDialog(
    project: Project,
    private val request: PermissionRequest,
) : DialogWrapper(project) {

    private var choice = PermissionChoice.ALLOW_ONCE

    init {
        title = "Claude Permission Request"
        setOKButtonText("Allow Once")
        setCancelButtonText("Deny")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val heading = JLabel("<html><b>Claude wants to ${request.action}</b></html>")
        heading.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(heading)

        if (request.detail.isNotBlank()) {
            panel.add(Box.createVerticalStrut(8))
            val detail = JTextArea(request.detail).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = Font("JetBrains Mono", Font.PLAIN, 12)
                background = UIManager.getColor("Panel.background")
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6),
                )
                alignmentX = Component.LEFT_ALIGNMENT
            }
            panel.add(detail)
        }

        panel.add(Box.createVerticalStrut(12))

        val group = ButtonGroup()
        listOf(
            "Allow once"                  to PermissionChoice.ALLOW_ONCE,
            "Always allow for this session" to PermissionChoice.ALLOW_ALWAYS,
            "Deny"                        to PermissionChoice.DENY,
        ).forEachIndexed { idx, (label, value) ->
            val radio = JRadioButton(label).apply {
                isSelected = idx == 0
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { choice = value }
            }
            group.add(radio)
            panel.add(radio)
        }

        panel.preferredSize = Dimension(440, panel.preferredSize.height)
        return panel
    }

    fun getChoice(): PermissionChoice = choice

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)
    override fun doOKAction() { super.doOKAction() }
    override fun doCancelAction() { choice = PermissionChoice.DENY; super.doCancelAction() }
}

// ── Plan Exit Dialog (used by HookServer for ExitPlanMode) ───────────────────

class PlanExitDialog(project: Project) : DialogWrapper(project) {

    init {
        title = "Exit Plan Mode"
        setOKButtonText("Start Coding")
        setCancelButtonText("Keep Planning")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val label = JLabel("<html><b>Claude is ready to start coding.</b><br><br>" +
                "Approve the plan and let Claude implement it?</html>")
        label.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(label)
        panel.preferredSize = Dimension(380, panel.preferredSize.height)
        return panel
    }
}

// ── Free-Text Question Dialog ─────────────────────────────────────────────────

class FreeTextQuestionDialog(
    project: Project,
    private val question: ParsedQuestion,
) : DialogWrapper(project) {

    private val textArea = JTextArea(4, 40)

    init {
        title = "Claude: ${question.title}"
        setOKButtonText("Send")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        if (question.body.isNotBlank()) {
            val bodyLabel = JLabel("<html><p style='width:350px'>${question.body.replace("\n", "<br>")}</p></html>")
            bodyLabel.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(bodyLabel)
            panel.add(Box.createVerticalStrut(12))
        }

        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.font = Font("JetBrains Mono", Font.PLAIN, 13)
        textArea.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(4, 6, 4, 6),
        )
        textArea.alignmentX = Component.LEFT_ALIGNMENT

        val scroll = JScrollPane(textArea).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(420, 100)
        }
        panel.add(scroll)
        panel.preferredSize = Dimension(440, panel.preferredSize.height)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea
    fun getText(): String = textArea.text.trim()
}
