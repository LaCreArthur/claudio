package com.lacrearthur.claudio

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.lacrearthur.claudio.test.ClaudioTestServiceImpl
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
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
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*

private val log = Logger.getInstance("Claudio")
internal val MONO_11 = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))

internal inline fun Project.withTestService(block: ClaudioTestServiceImpl.() -> Unit) {
    try { service<ClaudioTestServiceImpl>().block() } catch (_: Exception) {}
}

internal inline fun <T> Project.fromTestService(block: ClaudioTestServiceImpl.() -> T): T? =
    try { service<ClaudioTestServiceImpl>().block() } catch (_: Exception) { null }

internal fun buildErrorPanel(message: String): JPanel {
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
    private val resumeSessionId: String = "",
) : JPanel(BorderLayout()), Disposable {

    private var terminalView: TerminalView? = null
    private val terminalContainer = JPanel(BorderLayout())
    private val inputArea = object : JTextArea(3, 80) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isEmpty()) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.color = JBColor(Color(0xA0, 0xA0, 0xA0), Color(0x6A, 0x6A, 0x70))
                    g2.font = font.deriveFont(Font.ITALIC)
                    g2.drawString("Ask Claude... (commands /, files @)", insets.left, insets.top + g2.fontMetrics.ascent)
                } finally {
                    g2.dispose()
                }
            }
        }
    }
    private val statusLabel = JLabel(" Starting...")
    private lateinit var slashCompletion: SlashCommandCompletion
    private lateinit var atFileCompletion: AtFileCompletion
    private val outputParser = CliOutputParser()
    private val hookServer = HookServer(project)
    private val mcpServer = IdeMcpServer(project)

    // Prompt history: up/down arrow recalls previous sends
    private val promptHistory = mutableListOf<String>()
    private var historyIndex = -1   // -1 = not browsing; 0 = oldest

    @Volatile private var lastOutputTime = 0L
    private val activityTimer: Timer
    private var wasGenerating = false
    private val sessionTranscript = StringBuilder()
    private val modeLabel = JLabel("").apply {
        font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(10))
        foreground = JBColor.GRAY
        toolTipText = "Permission mode (Shift+Tab to cycle)"
    }

    init {
        log.warn("[CLAUDE] ClaudePanel init, project=${project.name} basePath=${project.basePath}")
        Disposer.register(parentDisposable, this)
        Disposer.register(this, hookServer)
        Disposer.register(this, mcpServer)

        // Hook-based control plane: structured JSON events from Claude Code
        HookInstaller.install()
        hookServer.start()

        // IDE MCP data plane: CLI auto-discovers via lockfile and connects
        mcpServer.start()
        IdeLockfileManager.write(mcpServer.port, project)

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
        project.withTestService { setTerminalLineInjector { text -> outputParser.feed(text) } }

        // Parser owns only AskUserQuestion. Permissions are owned by PermissionRequest hook.
        outputParser.onQuestion = { question ->
            log.warn("[CLAUDE] onQuestion fired: title='${question.title}' opts=${question.options.size} thread=${Thread.currentThread().name}")
            project.withTestService { recordParsedQuestion(question.title) }
            if (question.options.isEmpty()) showFreeTextDialog(question)
            else showAskUserDialog(question)
        }
        outputParser.onPermissionMode = { mode ->
            SwingUtilities.invokeLater { modeLabel.text = " · $mode" }
        }

        val inputBar = buildInputBar()
        val changedFilesPanel = ChangedFilesPanel(project, hookServer)

        val bottomPanel = JPanel(BorderLayout()).apply {
            add(changedFilesPanel, BorderLayout.NORTH)
            add(inputBar, BorderLayout.SOUTH)
        }

        add(terminalContainer, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)

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
                val effectiveModel = model.ifEmpty {
                    project.fromTestService { defaultTestModel }
                } ?: ""
                val modelFlag = if (effectiveModel.isNotEmpty()) " --model $effectiveModel" else ""
                val resumeFlag = if (resumeSessionId.isNotEmpty()) " --resume $resumeSessionId" else ""
                val envVars = buildList {
                    if (hookServer.port > 0) add("CLAUDIO_HOOK_PORT=${hookServer.port}")
                }
                val envPrefix = if (envVars.isNotEmpty()) envVars.joinToString(" ") + " " else ""
                val ideFlag = if (mcpServer.port > 0) " --ide" else ""
                val launchCmd = "${envPrefix}claude$modelFlag$resumeFlag$ideFlag"
                view.createSendTextBuilder().shouldExecute().send(launchCmd)
                log.warn("[CLAUDE] 'claude' sent")

                project.withTestService {
                    setSessionReady(true)
                    setCliProcessStatus("running")
                    setTerminalInputSender { text ->
                        view.coroutineScope.launch { view.createSendTextBuilder().shouldExecute().send(text) }
                    }
                }

                wireOutputListener(view)

                view.sessionState.first { it is TerminalViewSessionState.Terminated }
                log.warn("[CLAUDE] terminal Terminated - scheduling restart")
                updateStatusText("Session ended. Restarting...")
                project.withTestService {
                    setSessionReady(false)
                    setCliProcessStatus("exited(0)")
                }
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
                        project.withTestService { appendTranscript(newText) }
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
        project.withTestService { setActiveDialog("askUserQuestion") }
        SwingUtilities.invokeLater {
            val dialog = AskUserQuestionDialog(project, question)
            project.withTestService { setActiveDialog("askUserQuestion", dialog) }
            try {
                if (dialog.showAndGet()) {
                    answerQuestion(dialog.getSelectedOptionIndex(), dialog.isFreeTextSelected(), dialog.getFreeText())
                }
            } finally {
                project.withTestService { setActiveDialog(null) }
            }
        }
    }

    private fun showFreeTextDialog(question: ParsedQuestion) {
        log.warn("[CLAUDE] showFreeTextDialog: title='${question.title}'")
        project.withTestService { setActiveDialog("askUserQuestion") }
        SwingUtilities.invokeLater {
            val dialog = FreeTextQuestionDialog(project, question)
            project.withTestService { setActiveDialog("askUserQuestion", dialog) }
            try {
                if (dialog.showAndGet()) {
                    val text = dialog.getText()
                    if (text.isNotEmpty()) answerFreeText(text)
                }
            } finally {
                project.withTestService { setActiveDialog(null) }
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
            statusLabel.text = " $text"
            val nowReady = text == "Ready"
            if (wasGenerating && nowReady) notifyIfUnfocused()
            wasGenerating = text == "Generating..."
        }
    }

    private fun notifyIfUnfocused() {
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
        if (tw.isVisible) return
        val tabPanel = SwingUtilities.getAncestorOfClass(ClaudioTabbedPanel::class.java, this) as? ClaudioTabbedPanel
        val tabName = tabPanel?.getTabName(this) ?: "Claude"
        val notification = Notification("Claudio", "Claude finished", tabName, NotificationType.INFORMATION)
        notification.addAction(object : AnAction("Focus") {
            override fun actionPerformed(e: AnActionEvent) { tw.activate(null) }
        })
        Notifications.Bus.notify(notification, project)
    }

    fun exportSession() {
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
    fun sessionCost(): Double = 0.0

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

    fun injectBuildErrors() {
        val errors = mutableListOf<String>()
        val seen = mutableSetOf<Any>()
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
        } else {
            appendToInput("Build errors (${errors.size}):\n${errors.joinToString("\n") { "  $it" }}\n\n")
        }
    }

    private fun buildInputBar(): JPanel {
        val borderColor = JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x50, 0x50, 0x54))
        val arcRadius = JBUI.scale(12)

        // Outer wrapper: margin around the rounded card
        val wrapper = JPanel(BorderLayout())
        wrapper.border = BorderFactory.createEmptyBorder(4, 8, 6, 8)

        // The rounded card that contains text area + toolbar
        val card = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = background
                    g2.fillRoundRect(0, 0, width, height, arcRadius, arcRadius)
                } finally {
                    g2.dispose()
                }
            }

            override fun paintBorder(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = borderColor
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arcRadius, arcRadius)
                } finally {
                    g2.dispose()
                }
            }
        }
        card.isOpaque = false
        card.border = BorderFactory.createEmptyBorder(6, 10, 4, 10)
        card.background = JBColor(Color(0xFA, 0xFA, 0xFA), Color(0x2B, 0x2B, 0x2F))

        // --- Text area setup ---
        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(13))
        inputArea.border = BorderFactory.createEmptyBorder(4, 2, 4, 2)
        inputArea.isOpaque = false

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

        // Relay CC shortcuts from input bar -> terminal (Shift+Tab cycles permission mode)
        val shiftTabKey = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK)
        inputArea.getInputMap(JComponent.WHEN_FOCUSED).put(shiftTabKey, "relay-shift-tab")
        inputArea.actionMap.put("relay-shift-tab", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                terminalView?.coroutineScope?.launch { terminalView?.createSendTextBuilder()?.send("\u001b[Z") }
            }
        })

        // Cmd+V with an image on the clipboard -> save to temp PNG and inject the path
        inputArea.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_V, mask), "claudio-paste")
        inputArea.actionMap.put("claudio-paste", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    try {
                        val img = clipboard.getData(DataFlavor.imageFlavor) as java.awt.Image
                        val buf = BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB)
                        val g = buf.createGraphics()
                        g.drawImage(img, 0, 0, null)
                        g.dispose()
                        val tempFile = File.createTempFile("claudio-paste-", ".png")
                        tempFile.deleteOnExit()
                        ImageIO.write(buf, "png", tempFile)
                        appendToInput(tempFile.absolutePath)
                    } catch (_: Exception) {
                        inputArea.paste()
                    }
                } else {
                    inputArea.paste()
                }
            }
        })

        // Drag-and-drop files into input bar -> inserts @relative/path as context
        val defaultTransferHandler = inputArea.transferHandler
        inputArea.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                defaultTransferHandler?.canImport(support) == true

            override fun importData(support: TransferSupport): Boolean {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        val basePath = project.basePath ?: ""
                        val refs = files.joinToString(" ") { f ->
                            val rel = f.absolutePath.removePrefix(basePath).trimStart('/')
                            "@$rel"
                        }
                        appendToInput(" $refs")
                        return true
                    } catch (_: Exception) {}
                }
                return defaultTransferHandler?.importData(support) ?: false
            }
        }

        // Scrollable text area - no visible scroll border, card provides the frame
        val scroll = JScrollPane(inputArea)
        scroll.preferredSize = Dimension(0, JBUI.scale(68))
        scroll.border = BorderFactory.createEmptyBorder()
        scroll.isOpaque = false
        scroll.viewport.isOpaque = false

        // --- Bottom toolbar row ---
        val toolbarHeight = JBUI.scale(24)

        val histPrevBtn = JButton(AllIcons.Actions.Back).apply {
            toolTipText = "Previous prompt"
            preferredSize = Dimension(toolbarHeight, toolbarHeight)
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
            addActionListener { historyStep(-1) }
        }
        val histNextBtn = JButton(AllIcons.Actions.Forward).apply {
            toolTipText = "Next prompt"
            preferredSize = Dimension(toolbarHeight, toolbarHeight)
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
            addActionListener { historyStep(+1) }
        }

        statusLabel.font = Font("JetBrains Mono", Font.ITALIC, JBUI.scale(10))
        statusLabel.foreground = JBColor(Color(0x90, 0x90, 0x90), Color(0x78, 0x78, 0x80))

        modeLabel.font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(10))
        modeLabel.foreground = JBColor(Color(0x90, 0x90, 0x90), Color(0x78, 0x78, 0x80))

        val sendBtn = JButton(AllIcons.Actions.Execute).apply {
            toolTipText = "Send (Enter)"
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { sendText(inputArea.text) }
        }

        val toolbarLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            add(histPrevBtn)
            add(histNextBtn)
            add(modeLabel)
        }
        val toolbarRight = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            add(statusLabel)
            add(sendBtn)
        }
        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(2, 0, 0, 0)
            add(toolbarLeft, BorderLayout.WEST)
            add(toolbarRight, BorderLayout.EAST)
        }

        card.add(scroll, BorderLayout.CENTER)
        card.add(toolbar, BorderLayout.SOUTH)
        wrapper.add(card, BorderLayout.CENTER)
        return wrapper
    }

    override fun dispose() {
        log.warn("[CLAUDE] ClaudePanel disposed")
        IdeLockfileManager.delete(mcpServer.port)
        activityTimer.stop()
    }
}
