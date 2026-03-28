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
import com.intellij.openapi.application.ApplicationManager
import java.awt.*
import java.awt.event.*
import java.io.File
import javax.swing.*

private val log = Logger.getInstance("Claudio")
private val MONO_11 = Font("JetBrains Mono", Font.PLAIN, 11)

private data class SessionEntry(val preview: String, val timestamp: String, val sessionId: String)

private data class ClaudePreset(val name: String, val systemPrompt: String)

private val DEFAULT_PRESETS = listOf(
    ClaudePreset("Backend Agent", "You are a backend engineering specialist. Focus on server-side code, APIs, databases, and performance. Be direct and concise."),
    ClaudePreset("Review Agent", "You are a code reviewer. Analyze code for bugs, security issues, performance problems, and style. Give specific, actionable feedback."),
    ClaudePreset("Test Agent", "You are a testing specialist. Write unit tests, integration tests, and suggest edge cases. Prefer practical coverage over 100% coverage."),
)

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
    label.foreground = Color(220, 80, 80)
    label.background = panel.background
    label.font = Font("JetBrains Mono", Font.PLAIN, 12)
    panel.add(JScrollPane(label), BorderLayout.CENTER)
    return panel
}

class ClaudePanel(
    private val project: Project,
    parentDisposable: Disposable,
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
                val launchCmd = if (hookServer.port > 0)
                    "CLAUDIO_HOOK_PORT=${hookServer.port} claude"
                else
                    "claude"
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
        SwingUtilities.invokeLater { statusLabel.text = "  $text" }
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

    fun appendToInput(text: String) {
        inputArea.append(text)
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
        statusLabel.foreground = UIManager.getColor("Component.infoForeground") ?: Color.GRAY

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
        rightPanel.add(statusLabel)

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

    init {
        Disposer.register(parentDisposable, this)
        add(SessionHistoryPanel(), BorderLayout.WEST)
        addTab()
        tabbedPane.addTab(ADD_TAB, JPanel())
        tabbedPane.addChangeListener {
            if (addingTab) return@addChangeListener
            val idx = tabbedPane.selectedIndex
            if (idx >= 0 && idx == tabbedPane.tabCount - 1 && tabbedPane.getTitleAt(idx) == ADD_TAB) {
                addTab()
            }
        }

        // Preset launcher toolbar - sits above the tab strip, right-aligned.
        // Placement: NORTH of a wrapper panel that holds the tabbedPane in CENTER.
        // This keeps it physically adjacent to the "+" tab without touching SessionHistoryPanel.
        val presetBtn = JButton("⚡").apply {
            toolTipText = "Agent presets - open a new session with a pre-loaded agent"
            font = MONO_11
            isFocusable = false
        }
        presetBtn.addActionListener { e ->
            val menu = JPopupMenu()
            for (preset in DEFAULT_PRESETS) {
                menu.add(JMenuItem(preset.name).apply {
                    font = MONO_11
                    addActionListener {
                        addTab()
                        // The newly created tab is now selected; rename it and pre-fill input.
                        val newIdx = tabbedPane.selectedIndex
                        if (newIdx >= 0) {
                            tabbedPane.setTitleAt(newIdx, preset.name)
                            (tabbedPane.getTabComponentAt(newIdx) as? TabLabel)?.rename(preset.name)
                        }
                        appendToInput(preset.systemPrompt)
                    }
                })
            }
            val src = e.source as JComponent
            menu.show(src, 0, src.height)
        }

        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            add(presetBtn)
        }

        val centerWrapper = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(tabbedPane, BorderLayout.CENTER)
        }
        add(centerWrapper, BorderLayout.CENTER)
    }

    private fun addTab() {
        addingTab = true
        tabCounter++
        val panel = ClaudePanel(project, this)
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

    fun sendText(text: String) {
        (tabbedPane.selectedComponent as? ClaudePanel)?.sendText(text)
    }

    override fun dispose() {}

    /** Tab label with inline rename on double-click. */
    private inner class TabLabel(name: String) : JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)) {
        private val label = JLabel(name)

        init {
            isOpaque = false
            add(label)
            label.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) startEdit()
                }
            })
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

        init {
            preferredSize = Dimension(220, 0)
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Separator.foreground"))

            val header = JLabel("  History")
            header.font = MONO_11
            header.border = BorderFactory.createEmptyBorder(4, 0, 4, 0)

            sessionList.font = MONO_11
            sessionList.cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
                ): Component {
                    val entry = value as SessionEntry
                    val comp = super.getListCellRendererComponent(
                        list, "${entry.timestamp}  ${entry.preview}", index, isSelected, cellHasFocus,
                    )
                    (comp as JLabel).font = MONO_11
                    return comp
                }
            }

            add(header, BorderLayout.NORTH)
            add(JScrollPane(sessionList), BorderLayout.CENTER)

            sessionList.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val entry = sessionList.selectedValue ?: return
                        sendText("claude --resume ${entry.sessionId}")
                    }
                }
            })

            ApplicationManager.getApplication().executeOnPooledThread { loadSessions() }
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
            SwingUtilities.invokeLater { listModel.clear(); entries.forEach { listModel.addElement(it) } }
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
    }

    companion object {
        private const val ADD_TAB = "+"
    }
}
