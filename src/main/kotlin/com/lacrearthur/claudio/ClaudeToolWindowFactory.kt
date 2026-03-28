package com.lacrearthur.claudio

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.lacrearthur.claudio.test.ClaudioTestServiceImpl
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.terminal.view.TerminalContentChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalOutputModelListener
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import java.awt.*
import java.awt.event.*
import java.io.File
import java.nio.file.Path
import javax.swing.*

private val log = Logger.getInstance("Claudio")
private val MONO_11 = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))

private data class SessionEntry(val preview: String, val timestamp: String, val sessionId: String)

private data class CheckpointEntry(val hash: String, val message: String, val timeAgo: String)

private data class ClaudePreset(val name: String, val systemPrompt: String, val model: String = "")

private val DEFAULT_PRESETS = listOf(
    ClaudePreset("Backend Agent", "You are a backend engineering specialist. Focus on server-side code, APIs, databases, and performance. Be direct and concise."),
    ClaudePreset("Review Agent", "You are a code reviewer. Analyze code for bugs, security issues, performance problems, and style. Give specific, actionable feedback."),
    ClaudePreset("Test Agent", "You are a testing specialist. Write unit tests, integration tests, and suggest edge cases. Prefer practical coverage over 100% coverage."),
)

private object PresetStore {
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
                val objEnd = findObjectEnd(remaining)
                if (objEnd < 0) break
                val obj = remaining.substring(0, objEnd + 1)
                remaining = remaining.substring(objEnd + 1).trimStart(',', ' ', '\n', '\r', '\t')
                val name = extractJsonString(obj, "name") ?: continue
                val prompt = extractJsonString(obj, "systemPrompt") ?: continue
                val model = extractJsonString(obj, "model") ?: ""
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

    private fun findObjectEnd(s: String): Int {
        var depth = 0
        var inStr = false
        var escape = false
        for (i in s.indices) {
            val c = s[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inStr) { escape = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (inStr) continue
            if (c == '{') depth++
            if (c == '}') { depth--; if (depth == 0) return i }
        }
        return -1
    }

    private fun extractJsonString(obj: String, key: String): String? {
        val marker = "\"$key\":"
        val idx = obj.indexOf(marker)
        if (idx < 0) return null
        var i = idx + marker.length
        while (i < obj.length && obj[i] != '"') i++
        if (i >= obj.length) return null
        i++ // skip opening quote
        val sb = StringBuilder()
        while (i < obj.length && obj[i] != '"') {
            if (obj[i] == '\\' && i + 1 < obj.length) {
                when (obj[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(obj[i + 1])
                }
                i += 2
            } else {
                sb.append(obj[i])
                i++
            }
        }
        return sb.toString()
    }
}

private class PresetEditorDialog(
    project: com.intellij.openapi.project.Project,
    private val customPresets: MutableList<ClaudePreset>,
) : DialogWrapper(project, true) {

    private val listModel = DefaultListModel<String>()
    private val presetList = JList(listModel)
    private val nameField = JTextField()
    private val promptArea = JTextArea(8, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font("JetBrains Mono", Font.PLAIN, 12)
    }
    private val modelOptions = arrayOf("", "claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5-20251001")
    private val modelCombo = JComboBox(modelOptions).apply {
        font = Font("JetBrains Mono", Font.PLAIN, 12)
        setRenderer(object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = if (value == "") "Default" else value as String
                return this
            }
        })
    }
    private val addBtn = JButton("Add")
    private val editBtn = JButton("Save")
    private val deleteBtn = JButton("Delete")

    // Index into the unified list: 0..DEFAULT_PRESETS.lastIndex are built-ins
    private val allPresets: List<ClaudePreset> get() = DEFAULT_PRESETS + customPresets
    private var selectedIndex = -1

    init {
        title = "Edit Presets"
        init()
        rebuildList()
        presetList.addListSelectionListener {
            if (!it.valueIsAdjusting) onSelectionChanged()
        }
        editBtn.isEnabled = false
        deleteBtn.isEnabled = false
        modelCombo.isEnabled = false
        addBtn.addActionListener { onAdd() }
        editBtn.addActionListener { onSave() }
        deleteBtn.addActionListener { onDelete() }
    }

    override fun createCenterPanel(): JComponent {
        val leftPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(180, 0)
            add(JScrollPane(presetList), BorderLayout.CENTER)
            val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2)).apply {
                add(addBtn)
                add(editBtn)
                add(deleteBtn)
            }
            add(btnPanel, BorderLayout.SOUTH)
        }

        val rightPanel = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
            val topFields = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                val namePanel = JPanel(BorderLayout(4, 0)).apply {
                    add(JLabel("Name:"), BorderLayout.WEST)
                    add(nameField, BorderLayout.CENTER)
                }
                add(namePanel)
                add(Box.createVerticalStrut(4))
                val modelPanel = JPanel(BorderLayout(4, 0)).apply {
                    add(JLabel("Model:"), BorderLayout.WEST)
                    add(modelCombo, BorderLayout.CENTER)
                }
                add(modelPanel)
            }
            add(topFields, BorderLayout.NORTH)
            add(JLabel("System prompt:"), BorderLayout.CENTER)
            add(JScrollPane(promptArea), BorderLayout.SOUTH)
        }

        val split = JPanel(BorderLayout(8, 0))
        split.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        split.add(leftPanel, BorderLayout.WEST)
        split.add(rightPanel, BorderLayout.CENTER)
        return split
    }

    private fun rebuildList() {
        listModel.clear()
        for (p in DEFAULT_PRESETS) listModel.addElement("${p.name} (built-in)")
        for (p in customPresets) listModel.addElement(p.name)
    }

    private fun onSelectionChanged() {
        selectedIndex = presetList.selectedIndex
        if (selectedIndex < 0) {
            nameField.text = ""
            promptArea.text = ""
            nameField.isEditable = false
            promptArea.isEditable = false
            modelCombo.selectedItem = ""
            modelCombo.isEnabled = false
            editBtn.isEnabled = false
            deleteBtn.isEnabled = false
            return
        }
        val preset = allPresets[selectedIndex]
        nameField.text = preset.name
        promptArea.text = preset.systemPrompt
        modelCombo.selectedItem = preset.model
        val isBuiltIn = selectedIndex < DEFAULT_PRESETS.size
        nameField.isEditable = !isBuiltIn
        promptArea.isEditable = !isBuiltIn
        modelCombo.isEnabled = !isBuiltIn
        editBtn.isEnabled = !isBuiltIn
        deleteBtn.isEnabled = !isBuiltIn
    }

    private fun onAdd() {
        val name = "New Preset ${customPresets.size + 1}"
        customPresets.add(ClaudePreset(name, "", ""))
        rebuildList()
        val newIdx = DEFAULT_PRESETS.size + customPresets.size - 1
        presetList.selectedIndex = newIdx
        nameField.requestFocusInWindow()
        nameField.selectAll()
    }

    private fun onSave() {
        val idx = selectedIndex
        if (idx < DEFAULT_PRESETS.size) return
        val customIdx = idx - DEFAULT_PRESETS.size
        val newName = nameField.text.trim().ifEmpty { customPresets[customIdx].name }
        val newPrompt = promptArea.text
        val newModel = (modelCombo.selectedItem as? String) ?: ""
        customPresets[customIdx] = ClaudePreset(newName, newPrompt, newModel)
        rebuildList()
        presetList.selectedIndex = idx
    }

    private fun onDelete() {
        val idx = selectedIndex
        if (idx < DEFAULT_PRESETS.size) return
        val customIdx = idx - DEFAULT_PRESETS.size
        customPresets.removeAt(customIdx)
        rebuildList()
        val newSel = minOf(idx, listModel.size - 1)
        if (newSel >= 0) presetList.selectedIndex = newSel
    }

    override fun doOKAction() {
        // Auto-save any pending edit
        if (selectedIndex >= DEFAULT_PRESETS.size) onSave()
        PresetStore.save(customPresets)
        super.doOKAction()
    }
}

class ClaudeToolWindowFactory : ToolWindowFactory, DumbAware {
    override suspend fun isApplicableAsync(project: Project): Boolean = true

    // The 3 "deprecated API" warnings from Plugin Verifier are synthetic Kotlin
    // JVM bridge methods for old Java defaults deprecated in favor of plugin.xml
    // attributes + isApplicableAsync. Known false-positive (MP-3345 / MP-7604).

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        log.warn("[CLAUDE] createToolWindowContent called, project=${project.name}")
        try {
            val panel = ClaudioTabbedPanel(project, toolWindow.disposable)
            val content = ContentFactory.getInstance().createContent(panel, "", false)
            content.isCloseable = false
            toolWindow.contentManager.addContent(content)
            log.warn("[CLAUDE] content added successfully")
        } catch (e: Throwable) {
            log.error("[CLAUDE] FATAL: createToolWindowContent failed", e)
            // Show error panel so user can see what failed
            val errorPanel = buildErrorPanel("Plugin init failed:\n${e.javaClass.simpleName}: ${e.message}\n\nCheck idea.log for full stacktrace.")
            val content = ContentFactory.getInstance().createContent(errorPanel, "Error", false)
            toolWindow.contentManager.addContent(content)
        }
    }
}

private fun buildErrorPanel(message: String): JPanel {
    val panel = JPanel(BorderLayout())
    panel.border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
    val label = JTextArea(message)
    label.isEditable = false
    label.lineWrap = true
    label.wrapStyleWord = true
    label.foreground = JBColor(Color(220, 80, 80), Color(255, 100, 100))
    label.background = panel.background
    label.font = Font("JetBrains Mono", Font.PLAIN, 12)
    panel.add(JScrollPane(label), BorderLayout.CENTER)
    return panel
}

class ClaudePanel(
    private val project: Project,
    parentDisposable: Disposable,
    private val model: String = "",
) : JPanel(BorderLayout()), Disposable {

    private var terminalView: TerminalView? = null
    private val terminalContainer = JPanel(BorderLayout())
    private val inputArea = JTextArea(3, 80)
    private val statusLabel = JLabel("  Starting...")
    private val permModeBtn = JButton("⚡ ?").apply {
        toolTipText = "Permission mode - click to cycle (Shift+Tab)"
        font = MONO_11
    }
    private val buildBtn = JButton("⚠ Build").apply {
        toolTipText = "Inject current build errors into input bar"
        font = MONO_11
    }
    private lateinit var slashCompletion: SlashCommandCompletion
    private lateinit var atFileCompletion: AtFileCompletion
    private val outputParser = CliOutputParser()
    private val hookServer = HookServer(project)

    // Prompt history: up/down arrow recalls previous sends
    private val promptHistory = mutableListOf<String>()
    private var historyIndex = -1   // -1 = not browsing; 0 = oldest

    @Volatile private var lastOutputTime = 0L
    private val activityTimer: Timer
    private var wasGenerating = false
    private val sessionTranscript = StringBuilder()
    private var totalCostUsd = 0.0
    private var totalTokens = 0L
    private val costLabel = JLabel("").apply {
        font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(10))
        foreground = JBColor.GRAY
        toolTipText = "Session cost and token usage"
    }

    init {
        log.warn("[CLAUDE] ClaudePanel init, project=${project.name} basePath=${project.basePath}")
        Disposer.register(parentDisposable, this)
        Disposer.register(this, hookServer)

        // Hook-based control plane: structured JSON events from Claude Code
        HookInstaller.install()
        hookServer.start()

        try {
            launchClaude()
        } catch (e: Throwable) {
            log.error("[CLAUDE] launchClaude() threw in init", e)
            showError("launchClaude failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        slashCompletion = SlashCommandCompletion(inputArea) { cmd ->
            inputArea.text = "/${cmd.name} "
            inputArea.caretPosition = inputArea.text.length
        }
        atFileCompletion = AtFileCompletion(project, inputArea).also { it.preload() }

        // Register terminal line injector for integration tests
        try { project.service<ClaudioTestServiceImpl>().setTerminalLineInjector { text -> outputParser.feed(text) } } catch (_: Exception) {}

        // Parser owns only AskUserQuestion. Permissions are owned by PermissionRequest hook.
        outputParser.onQuestion = { question ->
            try { project.service<ClaudioTestServiceImpl>().recordParsedQuestion(question.title) } catch (_: Exception) {}
            if (question.options.isEmpty()) showFreeTextDialog(question)
            else showAskUserDialog(question)
        }
        outputParser.onPermissionMode = { mode ->
            SwingUtilities.invokeLater { permModeBtn.text = "⚡ $mode" }
        }
        outputParser.onCostLine = { cost, tokens ->
            totalCostUsd += cost
            totalTokens += tokens
            val tokStr = when {
                totalTokens >= 1_000_000 -> "${"%.1f".format(totalTokens / 1_000_000.0)}M"
                totalTokens >= 1_000 -> "${"%.1f".format(totalTokens / 1_000.0)}k"
                else -> "$totalTokens"
            }
            SwingUtilities.invokeLater { costLabel.text = "  \$${"%.4f".format(totalCostUsd)} · ${tokStr}" }
        }
        permModeBtn.addActionListener {
            val view = terminalView ?: return@addActionListener
            view.coroutineScope.launch { view.createSendTextBuilder().send("\u001b[Z") }
        }
        buildBtn.addActionListener { injectBuildErrors() }

        val inputBar = buildInputBar()

        add(terminalContainer, BorderLayout.CENTER)
        add(inputBar, BorderLayout.SOUTH)

        activityTimer = Timer(500) { updateStatus() }
        activityTimer.start()

        log.warn("[CLAUDE] ClaudePanel init complete")
    }

    private fun showError(msg: String) {
        log.error("[CLAUDE] showError: $msg")
        SwingUtilities.invokeLater {
            terminalContainer.removeAll()
            terminalContainer.add(buildErrorPanel(msg), BorderLayout.CENTER)
            terminalContainer.revalidate()
            terminalContainer.repaint()
        }
    }

    private fun launchClaude() {
        log.warn("[CLAUDE] launchClaude()")
        terminalContainer.removeAll()
        terminalView = null
        sessionTranscript.clear()
        totalCostUsd = 0.0
        totalTokens = 0L
        SwingUtilities.invokeLater { costLabel.text = "" }
        updateStatusText("Starting...")

        val tabsManager = try {
            TerminalToolWindowTabsManager.getInstance(project).also {
                log.warn("[CLAUDE] TerminalToolWindowTabsManager obtained: $it")
            }
        } catch (e: Throwable) {
            log.error("[CLAUDE] TerminalToolWindowTabsManager.getInstance failed", e)
            showError("TerminalToolWindowTabsManager unavailable:\n${e.message}\n\nIs Terminal plugin enabled?")
            return
        }

        val tab = try {
            tabsManager.createTabBuilder()
                .workingDirectory(project.basePath)
                .tabName("Claude")
                .shouldAddToToolWindow(false) // @Internal - no public API for detached tabs
                .deferSessionStartUntilUiShown(true)
                .requestFocus(true)
                .createTab()
                .also { log.warn("[CLAUDE] tab created: $it, view=${it.view}") }
        } catch (e: Throwable) {
            log.error("[CLAUDE] createTab() failed", e)
            showError("Terminal tab creation failed:\n${e.message}")
            return
        }

        terminalView = tab.view
        log.warn("[CLAUDE] adding tab.view.component to terminalContainer")
        terminalContainer.add(tab.view.component, BorderLayout.CENTER)
        terminalContainer.revalidate()
        terminalContainer.repaint()

        val view = tab.view

        view.coroutineScope.launch {
            try {
                log.warn("[CLAUDE] waiting for Running state...")
                view.sessionState.first { it is TerminalViewSessionState.Running }
                log.warn("[CLAUDE] terminal Running - sending 'claude'")
                updateStatusText("Launching Claude...")
                val modelFlag = if (model.isNotEmpty()) " --model $model" else ""
                val launchCmd = if (hookServer.port > 0)
                    "CLAUDIO_HOOK_PORT=${hookServer.port} claude$modelFlag"
                else
                    "claude$modelFlag"
                view.createSendTextBuilder().shouldExecute().send(launchCmd)
                log.warn("[CLAUDE] 'claude' sent")

                try {
                    val testSvc = project.service<ClaudioTestServiceImpl>()
                    testSvc.setSessionReady(true)
                    testSvc.setCliProcessStatus("running")
                    testSvc.setTerminalInputSender { text ->
                        view.coroutineScope.launch { view.createSendTextBuilder().shouldExecute().send(text) }
                    }
                } catch (_: Exception) {}

                wireOutputListener(view)

                view.sessionState.first { it is TerminalViewSessionState.Terminated }
                log.warn("[CLAUDE] terminal Terminated - scheduling restart")
                updateStatusText("Session ended. Restarting...")
                try {
                    val testSvc = project.service<ClaudioTestServiceImpl>()
                    testSvc.setSessionReady(false)
                    testSvc.setCliProcessStatus("exited(0)")
                } catch (_: Exception) {}
                delay(1500)
                SwingUtilities.invokeLater { launchClaude() }
            } catch (e: CancellationException) {
                throw e  // Never swallow cancellation - coroutines contract
            } catch (e: Throwable) {
                log.error("[CLAUDE] coroutine error in launchClaude", e)
                showError("Terminal session error:\n${e.message}")
            }
        }
    }

    private fun wireOutputListener(view: TerminalView) {
        log.warn("[CLAUDE] wireOutputListener")
        try {
            view.outputModels.regular.addListener(this, object : TerminalOutputModelListener {
                private val spinnerChars = setOf('✶', '✳', '✢', '·', '✻', '✽')
                override fun afterContentChanged(event: TerminalContentChangeEvent) {
                    if (event.isTypeAhead || event.isTrimming) return
                    lastOutputTime = System.currentTimeMillis()
                    val newText = event.newText.toString()
                    if (newText.isNotBlank()) {
                        // Skip logging pure spinner frames - they flood logs without signal
                        if (newText.trim().all { it in spinnerChars }) return
                        log.warn("[CLAUDE_OUT] ${newText.take(300).replace("\n", "\\n")}")
                        outputParser.feed(newText)
                        sessionTranscript.append(newText)
                        try { project.service<ClaudioTestServiceImpl>().appendTranscript(newText) } catch (_: Exception) {}
                    }
                }
            })
            log.warn("[CLAUDE] output listener wired")
        } catch (e: Throwable) {
            log.error("[CLAUDE] wireOutputListener failed", e)
        }
    }

    private fun showAskUserDialog(question: ParsedQuestion) {
        log.warn("[CLAUDE] showAskUserDialog: title='${question.title}' options=${question.options.size}")
        SwingUtilities.invokeLater {
            val dialog = AskUserQuestionDialog(project, question)
            if (dialog.showAndGet()) {
                answerQuestion(dialog.getSelectedOptionIndex(), dialog.isFreeTextSelected(), dialog.getFreeText())
            }
        }
    }

    private fun showFreeTextDialog(question: ParsedQuestion) {
        log.warn("[CLAUDE] showFreeTextDialog: title='${question.title}'")
        SwingUtilities.invokeLater {
            val dialog = FreeTextQuestionDialog(project, question)
            if (dialog.showAndGet()) {
                val text = dialog.getText()
                if (text.isNotEmpty()) answerFreeText(text)
            }
        }
    }

    private fun answerFreeText(text: String) {
        val view = terminalView ?: return
        log.warn("[CLAUDE] answerFreeText: '${text.take(80)}'")
        view.coroutineScope.launch {
            delay(200)
            view.createSendTextBuilder().shouldExecute().send(text)
        }
    }

    private fun answerQuestion(optionIndex: Int, isFreeText: Boolean, freeText: String) {
        val view = terminalView ?: return
        log.warn("[CLAUDE] answerQuestion idx=$optionIndex isFreeText=$isFreeText text='${freeText.take(80)}'")
        view.coroutineScope.launch {
            delay(200)
            repeat(optionIndex) {
                view.createSendTextBuilder().send("\u001b[B")
                delay(100)
            }
            if (isFreeText && freeText.isNotEmpty()) {
                // Navigate to the option but don't press Enter - CC lets you type directly
                // into the highlighted free-text field. Send text then \r to submit.
                delay(200)
                log.warn("[CLAUDE] answerQuestion sending free text '${freeText.take(80)}'")
                view.createSendTextBuilder().send(freeText + "\r")
            } else {
                view.createSendTextBuilder().send("\r")
            }
        }
    }

    private fun updateStatus() {
        val view = terminalView ?: return
        val state = view.sessionState.value
        when {
            state is TerminalViewSessionState.Terminated -> updateStatusText("Session ended")
            state is TerminalViewSessionState.NotStarted -> updateStatusText("Starting...")
            lastOutputTime == 0L -> updateStatusText("Ready")
            System.currentTimeMillis() - lastOutputTime < 1000 -> updateStatusText("Generating...")
            else -> updateStatusText("Ready")
        }
    }

    private fun updateStatusText(text: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = "  $text"
            val nowReady = text == "Ready"
            if (wasGenerating && nowReady) notifyIfUnfocused()
            wasGenerating = text == "Generating..."
        }
    }

    private fun notifyIfUnfocused() {
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
        if (tw.isVisible) return
        val tabPanel = SwingUtilities.getAncestorOfClass(ClaudioTabbedPanel::class.java, this) as? ClaudioTabbedPanel
        val tabName = tabPanel?.getTabName(this) ?: "Claude"
        val notification = Notification("Claudio", "Claude finished", tabName, NotificationType.INFORMATION)
        notification.addAction(object : AnAction("Focus") {
            override fun actionPerformed(e: AnActionEvent) { tw.activate(null) }
        })
        Notifications.Bus.notify(notification, project)
    }

    private fun exportSession() {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())
        val descriptor = FileSaverDescriptor("Export Session", "Save session transcript as Markdown", "md")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val baseVf = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val result = wrapper.save(baseVf, "claude-session-$date.md") ?: return
        val file = result.getVirtualFile(true) ?: return
        val content = buildMarkdown()
        ApplicationManager.getApplication().runWriteAction {
            file.setBinaryContent(content.toByteArray(Charsets.UTF_8))
        }
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun buildMarkdown(): String {
        val clean = sessionTranscript.toString()
            .replace(Regex("\u001b\\[[0-9;]*[A-Za-z]"), "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        return "# Claude Session\n\n```\n$clean\n```\n"
    }

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val view = terminalView ?: run {
            log.warn("[CLAUDE] sendText: no terminalView")
            return
        }
        log.warn("[CLAUDE] sendText: '${trimmed.take(80)}'")
        if (promptHistory.lastOrNull() != trimmed) promptHistory.add(trimmed)
        historyIndex = -1
        inputArea.text = ""
        view.preferredFocusableComponent.requestFocusInWindow()
        if (trimmed.contains('\n')) {
            // Multi-line: bracketed paste creates a [Pasted text] block (3+ lines) or echoes
            // the text inline (1-2 lines). Either way, listen for the output to confirm the
            // paste landed, then send \r - instant for any size, no fixed delay.
            val tail = trimmed.takeLast(80)
            view.coroutineScope.launch {
                val pasteReady = CompletableDeferred<Unit>()
                val tempDisposable = Disposer.newDisposable()
                Disposer.register(this@ClaudePanel, tempDisposable)
                view.outputModels.regular.addListener(tempDisposable, object : TerminalOutputModelListener {
                    override fun afterContentChanged(event: TerminalContentChangeEvent) {
                        val newText = event.newText.toString()
                        if (newText.contains("[Pasted text") || newText.contains(tail)) {
                            pasteReady.complete(Unit)
                            Disposer.dispose(tempDisposable)
                        }
                    }
                })
                view.createSendTextBuilder().useBracketedPasteMode().send(trimmed)
                try {
                    withTimeout(800) { pasteReady.await() }
                } catch (_: TimeoutCancellationException) {
                    log.warn("[CLAUDE] paste submit timeout - sending \\r anyway")
                    Disposer.dispose(tempDisposable)
                }
                delay(30)
                view.createSendTextBuilder().send("\r")
            }
        } else {
            view.createSendTextBuilder().shouldExecute().send(trimmed)
        }
    }

    private fun historyStep(direction: Int) {
        if (promptHistory.isEmpty()) return
        if (direction < 0) {
            // go older
            if (historyIndex == -1) historyIndex = promptHistory.size
            if (historyIndex > 0) {
                historyIndex--
                inputArea.text = promptHistory[historyIndex]
                inputArea.caretPosition = inputArea.document.length
                inputArea.requestFocusInWindow()
            }
        } else {
            // go newer
            if (historyIndex == -1) return
            historyIndex++
            if (historyIndex < promptHistory.size) {
                inputArea.text = promptHistory[historyIndex]
                inputArea.caretPosition = inputArea.document.length
            } else {
                historyIndex = -1
                inputArea.text = ""
            }
            inputArea.requestFocusInWindow()
        }
    }

    fun currentStatus(): String = statusLabel.text.trim()

    fun appendToInput(text: String) {
        inputArea.append(text)
        inputArea.requestFocusInWindow()
        inputArea.caretPosition = inputArea.document.length
    }

    fun setInputText(text: String) {
        inputArea.text = text
        inputArea.requestFocusInWindow()
        inputArea.caretPosition = inputArea.document.length
    }

    private fun injectBuildErrors() {
        val errors = mutableListOf<String>()
        val seen = mutableSetOf<Any>() // dedup: same Document appears in split views
        for (editor in FileEditorManager.getInstance(project).allEditors) {
            val textEditor = editor as? TextEditor ?: continue
            val document = textEditor.editor.document
            if (!seen.add(document)) continue
            val fileName = editor.file?.name ?: continue
            val model = DocumentMarkupModel.forDocument(document, project, false) ?: continue
            for (h in model.allHighlighters) {
                val info = h.errorStripeTooltip as? HighlightInfo ?: continue
                if (info.severity < HighlightSeverity.ERROR) continue
                val desc = info.description ?: continue
                val line = document.getLineNumber(h.startOffset) + 1
                errors.add("$fileName:$line: $desc")
            }
        }
        if (errors.isEmpty()) {
            appendToInput("Build passed ✓\n\n")
            buildBtn.text = "✓ Build"
        } else {
            appendToInput("Build errors (${errors.size}):\n${errors.joinToString("\n") { "  $it" }}\n\n")
            buildBtn.text = "⚠ ${errors.size}"
        }
    }

    private fun buildInputBar(): JPanel {
        val bar = JPanel(BorderLayout())
        bar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(4, 6, 4, 6),
        )

        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.font = Font("JetBrains Mono", Font.PLAIN, 13)
        inputArea.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        // Enter sends; Cmd+Enter inserts newline
        val sendKey = "send-prompt"
        val mask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        inputArea.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), sendKey)
        inputArea.actionMap.put(sendKey, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = sendText(inputArea.text)
        })
        inputArea.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask), "newline")
        inputArea.actionMap.put("newline", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) { inputArea.insert("\n", inputArea.caretPosition) }
        })

        inputArea.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape")
        inputArea.actionMap.put("escape", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                inputArea.text = ""
                terminalView?.preferredFocusableComponent?.requestFocusInWindow()
            }
        })

        // Relay CC shortcuts from input bar → terminal (Shift+Tab cycles permission mode)
        val shiftTabKey = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK)
        inputArea.getInputMap(JComponent.WHEN_FOCUSED).put(shiftTabKey, "relay-shift-tab")
        inputArea.actionMap.put("relay-shift-tab", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                terminalView?.coroutineScope?.launch { terminalView?.createSendTextBuilder()?.send("\u001b[Z") }
            }
        })

        val scroll = JScrollPane(inputArea)
        scroll.preferredSize = Dimension(0, 72)
        scroll.border = BorderFactory.createEmptyBorder()

        val sendBtn = JButton("Send")
        sendBtn.addActionListener { sendText(inputArea.text) }

        val histUpBtn = JButton("↑").apply {
            toolTipText = "Previous prompt"
            font = MONO_11
            addActionListener { historyStep(-1) }
        }
        val histDownBtn = JButton("↓").apply {
            toolTipText = "Next prompt"
            font = MONO_11
            addActionListener { historyStep(+1) }
        }

        statusLabel.font = Font("JetBrains Mono", Font.ITALIC, 11)
        statusLabel.foreground = UIManager.getColor("Component.infoForeground") ?: JBColor.GRAY

        val histPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(histUpBtn)
            add(Box.createHorizontalStrut(2))
            add(histDownBtn)
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        }

        val rightPanel = JPanel()
        rightPanel.layout = BoxLayout(rightPanel, BoxLayout.Y_AXIS)
        rightPanel.border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
        rightPanel.add(sendBtn)
        rightPanel.add(Box.createVerticalStrut(4))
        rightPanel.add(histPanel)
        rightPanel.add(Box.createVerticalStrut(4))
        rightPanel.add(permModeBtn)
        rightPanel.add(Box.createVerticalStrut(4))
        rightPanel.add(buildBtn)
        rightPanel.add(Box.createVerticalStrut(4))
        rightPanel.add(JButton("⬇").apply {
            toolTipText = "Export session to Markdown"
            font = MONO_11
            addActionListener { exportSession() }
        })
        rightPanel.add(Box.createVerticalStrut(4))
        rightPanel.add(statusLabel)
        rightPanel.add(Box.createVerticalStrut(2))
        rightPanel.add(costLabel)

        bar.add(scroll, BorderLayout.CENTER)
        bar.add(rightPanel, BorderLayout.EAST)
        return bar
    }

    override fun dispose() {
        log.warn("[CLAUDE] ClaudePanel disposed")
        activityTimer.stop()
    }
}

/**
 * Hosts one or more [ClaudePanel] tabs, each running an independent claude process.
 * The last tab is a "+" pseudo-tab - selecting it opens a new session.
 */
class ClaudioTabbedPanel(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()), Disposable {

    private val tabbedPane = JTabbedPane()
    private var tabCounter = 0
    private var addingTab = false // guards against ChangeListener re-entry (EDT only)
    private var defaultModel = "" // model used when opening new sessions via toolbar

    init {
        Disposer.register(parentDisposable, this)
        val topSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, SessionHistoryPanel(), CheckpointPanel()).apply {
            resizeWeight = 0.6
            dividerSize = 4
            isContinuousLayout = true
            border = null
        }
        val bottomSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, ChangedFilesPanel(), McpServersPanel()).apply {
            resizeWeight = 0.5
            dividerSize = 4
            isContinuousLayout = true
            border = null
        }
        val sidebarSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, topSplit, bottomSplit).apply {
            resizeWeight = 0.7
            dividerSize = 4
            isContinuousLayout = true
            border = null
        }
        add(sidebarSplit, BorderLayout.WEST)
        addTab()
        tabbedPane.addTab(ADD_TAB, JPanel())
        tabbedPane.addChangeListener {
            if (addingTab) return@addChangeListener
            val idx = tabbedPane.selectedIndex
            if (idx >= 0 && idx == tabbedPane.tabCount - 1 && tabbedPane.getTitleAt(idx) == ADD_TAB) {
                addTab()
            }
        }

        val presetBtn = JButton("⚡").apply {
            toolTipText = "Agent presets - open a new session with a pre-loaded agent"
            font = MONO_11
            isFocusable = false
        }
        presetBtn.addActionListener { e ->
            val customPresets = PresetStore.load().toMutableList()
            val menu = JPopupMenu()
            for (preset in DEFAULT_PRESETS + customPresets) {
                menu.add(JMenuItem(preset.name).apply {
                    font = MONO_11
                    addActionListener {
                        addTab(model = preset.model)
                        val newIdx = tabbedPane.selectedIndex
                        if (newIdx >= 0) {
                            (tabbedPane.getTabComponentAt(newIdx) as? TabLabel)?.rename(preset.name)
                        }
                        appendToInput(preset.systemPrompt)
                    }
                })
            }
            menu.addSeparator()
            menu.add(JMenuItem("Edit presets...").apply {
                font = MONO_11
                addActionListener {
                    val mutable = PresetStore.load().toMutableList()
                    val dialog = PresetEditorDialog(project, mutable)
                    dialog.show()
                }
            })
            val src = e.source as JComponent
            menu.show(src, 0, src.height)
        }

        val newSessionBtn = JButton("+").apply {
            font = MONO_11
            toolTipText = "New session"
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            addActionListener { addTab(model = defaultModel) }
        }

        val modelNames = arrayOf("Default", "claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5-20251001")
        val modelBox = JComboBox(modelNames).apply {
            font = MONO_11
            toolTipText = "Model for new sessions"
            preferredSize = Dimension(JBUI.scale(190), JBUI.scale(22))
            isFocusable = false
            addActionListener { defaultModel = if (selectedIndex == 0) "" else selectedItem as String }
        }

        val settingsBtn = JButton("⚙").apply {
            font = MONO_11
            toolTipText = "Open presets.json"
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            addActionListener {
                val f = File(System.getProperty("user.home"), ".claudio/presets.json")
                f.parentFile?.mkdirs()
                if (!f.exists()) f.writeText("[]")
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
                if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(newSessionBtn)
            add(modelBox)
            add(presetBtn)
            add(settingsBtn)
        }

        val centerWrapper = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(tabbedPane, BorderLayout.CENTER)
        }
        add(centerWrapper, BorderLayout.CENTER)
    }

    private fun addTab(model: String = "") {
        addingTab = true
        tabCounter++
        val panel = ClaudePanel(project, this, model)
        val insertAt = if (tabbedPane.tabCount > 0 && tabbedPane.getTitleAt(tabbedPane.tabCount - 1) == ADD_TAB)
            tabbedPane.tabCount - 1
        else
            tabbedPane.tabCount
        tabbedPane.insertTab("Claude $tabCounter", null, panel, null, insertAt)
        tabbedPane.setTabComponentAt(insertAt, TabLabel("Claude $tabCounter"))
        tabbedPane.selectedIndex = insertAt
        addingTab = false
    }

    fun appendToInput(text: String) {
        (tabbedPane.selectedComponent as? ClaudePanel)?.appendToInput(text)
    }

    fun setInputText(text: String) {
        (tabbedPane.selectedComponent as? ClaudePanel)?.setInputText(text)
    }

    fun sendText(text: String) {
        (tabbedPane.selectedComponent as? ClaudePanel)?.sendText(text)
    }

    fun currentStatus(): String {
        val panel = tabbedPane.selectedComponent as? ClaudePanel ?: return "Off"
        return panel.currentStatus()
    }

    fun getTabName(panel: ClaudePanel): String {
        val idx = tabbedPane.indexOfComponent(panel)
        return if (idx >= 0) tabbedPane.getTitleAt(idx) else "Claude"
    }

    override fun dispose() {}

    /** Tab label with inline rename on double-click. */
    private inner class TabLabel(name: String) : JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)) {
        private val label = JLabel(name)
        private val closeBtn = JButton("×").apply {
            font = MONO_11
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            toolTipText = "Close tab"
            preferredSize = Dimension(16, 16)
            addActionListener { closeThisTab() }
        }

        init {
            isOpaque = false
            add(label)
            add(closeBtn)
            label.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) startEdit()
                }
            })
        }

        private fun closeThisTab() {
            val realTabCount = tabbedPane.tabCount - 1  // subtract the "+" tab
            if (realTabCount <= 1) return
            val idx = tabbedPane.indexOfTabComponent(this)
            if (idx < 0) return
            val panel = tabbedPane.getComponentAt(idx) as? ClaudePanel ?: return
            Disposer.dispose(panel)
            tabbedPane.removeTabAt(idx)
        }

        fun rename(newName: String) {
            label.text = newName
            val idx = tabbedPane.indexOfTabComponent(this)
            if (idx >= 0) tabbedPane.setTitleAt(idx, newName)
            revalidate()
            repaint()
        }

        private fun startEdit() {
            val idx = tabbedPane.indexOfTabComponent(this)
            if (idx < 0) return
            val field = JTextField(label.text, 10)
            var committed = false
            fun commit() {
                if (committed) return
                committed = true
                val name = field.text.trim().ifEmpty { label.text }
                label.text = name
                tabbedPane.setTitleAt(idx, name)
                remove(field)
                add(label)
                revalidate()
                repaint()
            }
            remove(label)
            add(field)
            revalidate()
            repaint()
            field.selectAll()
            field.requestFocusInWindow()
            field.addActionListener { commit() }
            field.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) { commit() }
            })
        }
    }

    /** Read-only sidebar listing recent Claude Code sessions from ~/.claude/projects/. */
    private inner class SessionHistoryPanel : JPanel(BorderLayout()) {
        private val listModel = DefaultListModel<SessionEntry>()
        private val sessionList = JList(listModel)
        private val pinnedIds: MutableSet<String> = loadPinned()

        init {
            preferredSize = Dimension(220, 0)
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Separator.foreground"))

            sessionList.font = MONO_11
            sessionList.cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
                ): Component {
                    val entry = value as SessionEntry
                    val prefix = if (entry.sessionId in pinnedIds) "★ " else ""
                    val comp = super.getListCellRendererComponent(
                        list, "$prefix${entry.timestamp}  ${entry.preview}", index, isSelected, cellHasFocus,
                    )
                    (comp as JLabel).font = MONO_11
                    return comp
                }
            }

            val header = JLabel("  History").apply {
                font = MONO_11
                border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
            }
            val historySection = JPanel(BorderLayout()).apply {
                add(header, BorderLayout.NORTH)
                add(JScrollPane(sessionList), BorderLayout.CENTER)
            }

            add(buildClaudemdIndicator(), BorderLayout.NORTH)
            add(historySection, BorderLayout.CENTER)

            sessionList.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        val entry = sessionList.selectedValue ?: return
                        sendText("claude --resume ${entry.sessionId}")
                    }
                }
                override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
                override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
                private fun showContextMenu(e: MouseEvent) {
                    val idx = sessionList.locationToIndex(e.point)
                    if (idx < 0) return
                    sessionList.selectedIndex = idx
                    val entry = listModel.getElementAt(idx) ?: return
                    val isPinned = entry.sessionId in pinnedIds
                    val menu = JPopupMenu()
                    menu.add(JMenuItem(if (isPinned) "Unpin" else "Pin").apply {
                        font = MONO_11
                        addActionListener {
                            if (isPinned) pinnedIds.remove(entry.sessionId)
                            else pinnedIds.add(entry.sessionId)
                            savePinned()
                            ApplicationManager.getApplication().executeOnPooledThread { loadSessions() }
                        }
                    })
                    menu.show(sessionList, e.x, e.y)
                }
            })

            ApplicationManager.getApplication().executeOnPooledThread { loadSessions() }
        }

        private fun loadPinned(): MutableSet<String> {
            val file = File(System.getProperty("user.home"), ".claudio/pinned.json")
            if (!file.exists()) return mutableSetOf()
            return try {
                val text = file.readText().trim().trimStart('[').trimEnd(']')
                text.split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toMutableSet()
            } catch (_: Exception) { mutableSetOf() }
        }

        private fun savePinned() {
            val file = File(System.getProperty("user.home"), ".claudio/pinned.json")
            file.parentFile.mkdirs()
            file.writeText("[${pinnedIds.joinToString(",") { "\"$it\"" }}]")
        }

        private fun loadSessions() {
            val claudeDir = File(System.getProperty("user.home"), ".claude/projects")
            if (!claudeDir.exists()) return
            val fmt = java.text.SimpleDateFormat("MMM d HH:mm")
            val entries = claudeDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".jsonl") }
                .map { it to it.lastModified() }
                .sortedByDescending { it.second }
                .take(20)
                .map { (file, mtime) ->
                    SessionEntry(extractFirstUserMessage(file), fmt.format(java.util.Date(mtime)), file.nameWithoutExtension)
                }
                .toList()
            val sorted = entries.sortedWith(compareByDescending<SessionEntry> { it.sessionId in pinnedIds }.thenByDescending { it.timestamp })
            SwingUtilities.invokeLater { listModel.clear(); sorted.forEach { listModel.addElement(it) } }
        }

        private fun extractFirstUserMessage(file: File): String {
            return try {
                file.bufferedReader().useLines { lines ->
                    lines.firstNotNullOfOrNull { line ->
                        if (!line.contains("\"role\":\"user\"")) return@firstNotNullOfOrNull null
                        // Handles both "content":"plain string" and "content":[{"text":"..."}]
                        for (marker in listOf("\"content\":\"", "\"text\":\"")) {
                            val idx = line.indexOf(marker)
                            if (idx < 0) continue
                            val start = idx + marker.length
                            if (start < line.length && line[start] == '[') continue // array, not string
                            val sb = StringBuilder()
                            var i = start
                            while (i < line.length && line[i] != '"') {
                                if (line[i] == '\\') { i += 2; continue }
                                sb.append(line[i])
                                i++
                            }
                            val text = sb.toString().trim().take(50)
                            if (text.isNotEmpty()) return@firstNotNullOfOrNull text
                        }
                        null
                    } ?: "(no message)"
                }
            } catch (_: Exception) { "(unreadable)" }
        }

        private fun findClaudemdFiles(): List<String> {
            val results = mutableListOf<String>()
            val home = System.getProperty("user.home")
            var dir: File? = project.basePath?.let { File(it) }
            while (dir != null && dir.absolutePath.startsWith(home)) {
                val candidate = File(dir, "CLAUDE.md")
                if (candidate.exists()) results.add(candidate.absolutePath)
                dir = dir.parentFile
            }
            val global = File(home, ".claude/CLAUDE.md")
            if (global.exists() && !results.contains(global.absolutePath)) results.add(global.absolutePath)
            return results
        }

        private fun buildClaudemdIndicator(): JPanel {
            val panel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4),
                )
            }
            panel.add(JLabel("  CLAUDE.md").apply { font = MONO_11 })
            val home = System.getProperty("user.home")
            val files = findClaudemdFiles()
            if (files.isEmpty()) {
                panel.add(JLabel("  (none found)").apply { font = MONO_11; foreground = JBColor.GRAY })
            } else {
                for (path in files) {
                    val rel = when {
                        path.startsWith(project.basePath ?: "\u0000") -> "./" + path.removePrefix(project.basePath!!).trimStart('/')
                        path.startsWith(home) -> "~/" + path.removePrefix(home).trimStart('/')
                        else -> path
                    }
                    panel.add(JLabel("  \u2514 $rel").apply {
                        font = MONO_11
                        cursor = Cursor(Cursor.HAND_CURSOR)
                        toolTipText = path
                        foreground = UIManager.getColor("Link.activeForeground") ?: JBUI.CurrentTheme.Link.linkColor()
                        addMouseListener(object : MouseAdapter() {
                            override fun mouseClicked(e: MouseEvent) {
                                val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
                                OpenFileDescriptor(project, vf).navigate(true)
                            }
                        })
                    })
                }
            }
            return panel
        }
    }

    /** Sidebar panel listing recent git checkpoints (commits) with diff preview on double-click. */
    private inner class CheckpointPanel : JPanel(BorderLayout()) {
        private val listModel = DefaultListModel<CheckpointEntry>()
        private val checkpointList = JList(listModel)
        private val gitRoot: File? by lazy { findGitRoot() }

        init {
            preferredSize = Dimension(220, 0)
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Separator.foreground"))

            val header = JLabel("  Checkpoints")
            header.font = MONO_11
            header.border = BorderFactory.createEmptyBorder(4, 0, 4, 0)

            checkpointList.font = MONO_11
            checkpointList.cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
                ): Component {
                    val entry = value as CheckpointEntry
                    val comp = super.getListCellRendererComponent(
                        list, "${entry.hash}  ${entry.message}", index, isSelected, cellHasFocus,
                    )
                    (comp as JLabel).apply {
                        font = MONO_11
                        toolTipText = "${entry.message}  (${entry.timeAgo})"
                    }
                    return comp
                }
            }

            add(header, BorderLayout.NORTH)
            add(JScrollPane(checkpointList), BorderLayout.CENTER)

            checkpointList.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val entry = checkpointList.selectedValue ?: return
                        showDiffPopup(entry)
                    }
                }
            })

            ApplicationManager.getApplication().executeOnPooledThread { loadCheckpoints() }
        }

        private fun loadCheckpoints() {
            val root = gitRoot ?: return
            try {
                val proc = ProcessBuilder("git", "log", "--format=%h|%s|%cr", "-10")
                    .directory(root)
                    .redirectErrorStream(true)
                    .start()
                val lines = proc.inputStream.bufferedReader().readLines()
                proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                proc.destroy()
                val entries = lines.mapNotNull { line ->
                    val parts = line.split("|", limit = 3)
                    if (parts.size < 3) return@mapNotNull null
                    CheckpointEntry(parts[0].trim(), parts[1].trim().take(45), parts[2].trim())
                }
                SwingUtilities.invokeLater {
                    listModel.clear()
                    entries.forEach { listModel.addElement(it) }
                }
            } catch (_: Exception) {
                // Not a git repo or git not available - stay empty silently
            }
        }

        private fun findGitRoot(): File? {
            val basePath = project.basePath ?: return null
            var dir = File(basePath)
            while (dir.exists()) {
                if (File(dir, ".git").exists()) return dir
                dir = dir.parentFile ?: break
            }
            return null
        }

        private fun showDiffPopup(entry: CheckpointEntry) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val root = gitRoot ?: return@executeOnPooledThread
                try {
                    val proc = ProcessBuilder("git", "show", entry.hash)
                        .directory(root)
                        .redirectErrorStream(true)
                        .start()
                    val output = proc.inputStream.bufferedReader().readLines().take(2000).joinToString("\n")
                    proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    proc.destroy()
                    // Strip ANSI escape sequences
                    val clean = output.replace(Regex("\u001b\\[[0-9;]*m"), "")
                    SwingUtilities.invokeLater {
                        val textArea = JTextArea(clean).apply {
                            isEditable = false
                            font = MONO_11
                            caretPosition = 0
                        }
                        val scroll = JScrollPane(textArea).apply {
                            preferredSize = Dimension(800, 600)
                        }
                        val dialog = object : DialogWrapper(project, true) {
                            init {
                                title = "${entry.hash} - ${entry.message}"
                                init()
                            }
                            override fun createCenterPanel() = scroll
                            override fun createActions() = arrayOf(okAction)
                        }
                        dialog.show()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /** Sidebar panel listing files modified during the current session (VFS listener). */
    private inner class ChangedFilesPanel : JPanel(BorderLayout()) {
        private val listModel = DefaultListModel<String>()
        private val fileList = JList(listModel)
        private val seen = mutableSetOf<String>()

        init {
            preferredSize = Dimension(220, 0)
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Separator.foreground"))

            val header = JLabel("  Changed Files")
            header.font = MONO_11
            header.border = BorderFactory.createEmptyBorder(4, 0, 4, 0)

            fileList.font = MONO_11
            add(header, BorderLayout.NORTH)
            add(JScrollPane(fileList), BorderLayout.CENTER)

            fileList.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val rel = fileList.selectedValue ?: return
                        showDiff(rel)
                    }
                }
            })

            project.messageBus.connect(this@ClaudioTabbedPanel).subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        val basePath = project.basePath ?: return
                        for (event in events) {
                            if (event !is VFileContentChangeEvent && event !is VFileCreateEvent) continue
                            val path = event.path
                            if (!path.startsWith(basePath)) continue
                            val rel = path.removePrefix(basePath).trimStart('/')
                            if (rel.startsWith(".git/") || rel.startsWith("build/") || rel.startsWith("node_modules/")) continue
                            SwingUtilities.invokeLater { if (seen.add(rel)) listModel.addElement(rel) }
                        }
                    }
                }
            )
        }

        private fun showDiff(rel: String) {
            val basePath = project.basePath ?: return
            val vFile = LocalFileSystem.getInstance().findFileByPath("$basePath/$rel") ?: return
            val headText = try {
                ProcessBuilder("git", "show", "HEAD:$rel")
                    .directory(File(basePath))
                    .start()
                    .inputStream.readBytes()
                    .toString(vFile.charset)
            } catch (_: Exception) { "(new file - not in git)" }
            val factory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(
                rel,
                factory.create(headText),
                factory.create(project, vFile),
                "HEAD",
                "Current"
            )
            DiffManager.getInstance().showDiff(project, request, DiffDialogHints.DEFAULT)
        }
    }

    /** Sidebar panel listing MCP servers configured in ~/.claude/settings.json. */
    private inner class McpServersPanel : JPanel(BorderLayout()) {
        private val listModel = DefaultListModel<String>()
        private val serverList = JList(listModel)

        init {
            preferredSize = Dimension(220, 0)
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Separator.foreground"))
            val headerLabel = JLabel("  MCP Servers").apply {
                font = MONO_11
                border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
            }
            val addBtn = JButton("+").apply {
                font = MONO_11
                toolTipText = "Add MCP server"
                isFocusable = false
                isContentAreaFilled = false
                isBorderPainted = false
                addActionListener { showAddDialog() }
            }
            val headerPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(headerLabel, BorderLayout.WEST)
                add(addBtn, BorderLayout.EAST)
            }
            serverList.font = MONO_11
            add(headerPanel, BorderLayout.NORTH)
            add(JScrollPane(serverList), BorderLayout.CENTER)
            serverList.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) openSettings()
                }
            })
            ApplicationManager.getApplication().executeOnPooledThread { loadServers() }
        }

        private fun loadServers() {
            val settingsFile = File(System.getProperty("user.home"), ".claude/settings.json")
            val entries = mutableListOf<String>()
            try {
                if (!settingsFile.exists()) { entries.add("(none configured)"); return }
                val text = settingsFile.readText()
                val mcpIdx = text.indexOf("\"mcpServers\"")
                if (mcpIdx < 0) { entries.add("(none configured)"); return }
                val objStart = text.indexOf('{', mcpIdx + 12)
                if (objStart < 0) { entries.add("(none configured)"); return }
                val objEnd = jsonObjectEnd(text, objStart)
                if (objEnd < 0) { entries.add("(none configured)"); return }
                val mcpObj = text.substring(objStart + 1, objEnd)
                var pos = 0
                while (pos < mcpObj.length) {
                    val q1 = mcpObj.indexOf('"', pos)
                    if (q1 < 0) break
                    val q2 = mcpObj.indexOf('"', q1 + 1)
                    if (q2 < 0) break
                    val name = mcpObj.substring(q1 + 1, q2)
                    val serverStart = mcpObj.indexOf('{', q2 + 1)
                    if (serverStart < 0) break
                    val serverEnd = jsonObjectEnd(mcpObj, serverStart)
                    if (serverEnd < 0) break
                    val serverObj = mcpObj.substring(serverStart + 1, serverEnd)
                    val type = jsonStringField(serverObj, "type") ?: "stdio"
                    entries.add("$name  ($type)")
                    pos = serverEnd + 1
                }
                if (entries.isEmpty()) entries.add("(none configured)")
            } catch (_: Exception) {
                entries.add("(error reading settings)")
            }
            SwingUtilities.invokeLater { entries.forEach { listModel.addElement(it) } }
        }

        private fun openSettings() {
            val f = File(System.getProperty("user.home"), ".claude/settings.json")
            if (!f.exists()) return
            val vf = LocalFileSystem.getInstance().findFileByPath(f.absolutePath) ?: return
            SwingUtilities.invokeLater { OpenFileDescriptor(project, vf).navigate(true) }
        }

        private fun jsonObjectEnd(s: String, start: Int): Int {
            var depth = 0; var inStr = false; var escape = false
            for (i in start until s.length) {
                val c = s[i]
                if (escape) { escape = false; continue }
                if (c == '\\' && inStr) { escape = true; continue }
                if (c == '"') { inStr = !inStr; continue }
                if (inStr) continue
                if (c == '{') depth++
                if (c == '}') { depth--; if (depth == 0) return i }
            }
            return -1
        }

        private fun jsonStringField(obj: String, key: String): String? {
            val marker = "\"$key\""
            val idx = obj.indexOf(marker)
            if (idx < 0) return null
            val colon = obj.indexOf(':', idx + marker.length)
            if (colon < 0) return null
            var i = colon + 1
            while (i < obj.length && obj[i] != '"') i++
            if (i >= obj.length) return null
            i++
            val sb = StringBuilder()
            while (i < obj.length && obj[i] != '"') {
                if (obj[i] == '\\') { i += 2; continue }
                sb.append(obj[i++])
            }
            return sb.toString().ifEmpty { null }
        }

        private fun showAddDialog() {
            val dialog = AddMcpServerDialog()
            if (!dialog.showAndGet()) return
            val (name, command, args) = dialog.getEntry() ?: return
            ApplicationManager.getApplication().executeOnPooledThread {
                addServerToSettings(name, command, args)
            }
        }

        private fun addServerToSettings(name: String, command: String, args: List<String>) {
            val settingsFile = File(System.getProperty("user.home"), ".claude/settings.json")
            settingsFile.parentFile.mkdirs()
            val argsJson = args.joinToString(", ") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
            val entryJson = buildString {
                append("    \"$name\": {\n      \"command\": \"$command\"")
                if (args.isNotEmpty()) append(",\n      \"args\": [$argsJson]")
                append("\n    }")
            }
            val original = if (settingsFile.exists()) settingsFile.readText() else "{}"
            val mcpIdx = original.indexOf("\"mcpServers\"")
            val newContent = if (mcpIdx < 0) {
                val lastBrace = original.lastIndexOf('}')
                if (lastBrace < 0) "{\n  \"mcpServers\": {\n$entryJson\n  }\n}"
                else {
                    val prefix = original.substring(0, lastBrace).trimEnd()
                    val sep = if (prefix == "{") "" else ","
                    "$prefix$sep\n  \"mcpServers\": {\n$entryJson\n  }\n}"
                }
            } else {
                val objStart = original.indexOf('{', mcpIdx + 12)
                if (objStart < 0) return
                val objEnd = jsonObjectEnd(original, objStart)
                if (objEnd < 0) return
                val inner = original.substring(objStart + 1, objEnd).trim()
                val sep = if (inner.isEmpty()) "" else ","
                original.substring(0, objEnd) + "$sep\n$entryJson\n  " + original.substring(objEnd)
            }
            settingsFile.writeText(newContent)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(settingsFile)
            SwingUtilities.invokeLater {
                listModel.clear()
                ApplicationManager.getApplication().executeOnPooledThread { loadServers() }
            }
        }

        private inner class AddMcpServerDialog : DialogWrapper(project) {
            private val nameField = JTextField(20)
            private val commandField = JTextField(20)
            private val argsField = JTextField(20)

            init {
                title = "Add MCP Server"
                init()
            }

            override fun createCenterPanel(): JComponent {
                val panel = JPanel(GridLayout(3, 2, 4, 6))
                panel.add(JLabel("Name:")); panel.add(nameField)
                panel.add(JLabel("Command:")); panel.add(commandField)
                panel.add(JLabel("Args (comma-separated):")); panel.add(argsField)
                return panel
            }

            fun getEntry(): Triple<String, String, List<String>>? {
                val name = nameField.text.trim()
                val command = commandField.text.trim()
                if (name.isEmpty() || command.isEmpty()) return null
                val args = argsField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                return Triple(name, command, args)
            }
        }
    }

    companion object {
        private const val ADD_TAB = "+"
    }
}
