package com.lacrearthur.claudio.test

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.lacrearthur.claudio.AskUserQuestionDialog
import com.lacrearthur.claudio.FreeTextQuestionDialog
import com.lacrearthur.claudio.PermissionChoice
import com.lacrearthur.claudio.PermissionDialog
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import com.intellij.openapi.application.ApplicationManager

private val log = Logger.getInstance("ClaudioTestService")

@Service(Service.Level.PROJECT)
class ClaudioTestServiceImpl(private val project: Project) : ClaudioTestService {

    private val lastEvent          = AtomicReference<String?>(null)
    private val lastResponse       = AtomicReference<String?>(null)
    private val activeDialog       = AtomicReference<String?>(null)
    private val lastParsedQuestion = AtomicReference<String?>(null)
    private val lastPromptMatch    = AtomicReference<String?>(null)
    @Volatile var defaultTestModel: String? = null
    @Volatile var changedFilesAccessor: (() -> Map<String, Pair<String, String>>)? = null
    @Volatile var changedFilesClearer: (() -> Unit)? = null
    @Volatile private var hookPort          = 0
    @Volatile private var sessionReady      = false
    @Volatile private var cliProcessStatus  = "not_started"
    @Volatile private var terminalLineInjector:  ((String) -> Unit)? = null
    @Volatile private var terminalInputSender:   ((String) -> Unit)? = null
    private val transcriptLock = Any()
    private val transcriptBuffer = StringBuilder(1024)

    // ── Wiring hooks (called from HookServer / ClaudePanel) ─────────────────

    fun setHookPort(port: Int) {
        hookPort = port
        log.warn("[TEST] hook port registered: $port")
    }

    private val activeDialogRef = AtomicReference<DialogWrapper?>(null)

    fun recordEvent(json: String)        { lastEvent.set(json) }
    fun recordResponse(json: String)     { lastResponse.set(json) }
    fun setActiveDialog(type: String?, dialog: DialogWrapper? = null) {
        log.warn("[TEST] setActiveDialog: type=$type dialog=${dialog?.javaClass?.simpleName}")
        activeDialog.set(type)
        activeDialogRef.set(dialog)
    }
    fun recordParsedQuestion(title: String) { lastParsedQuestion.set(title) }

    fun setTerminalLineInjector(callback: (String) -> Unit) { terminalLineInjector = callback }
    fun setTerminalInputSender(callback: (String) -> Unit)  { terminalInputSender  = callback }

    fun setSessionReady(ready: Boolean) {
        sessionReady = ready
        log.warn("[TEST] sessionReady=$ready")
    }

    fun setCliProcessStatus(status: String) {
        cliProcessStatus = status
        log.warn("[TEST] cliProcessStatus=$status")
    }

    fun appendTranscript(text: String) {
        synchronized(transcriptLock) {
            transcriptBuffer.append(text)
            if (transcriptBuffer.length > 1000) {
                transcriptBuffer.delete(0, transcriptBuffer.length - 1000)
            }
        }
        // Detect Claude ready prompt: line ending with "> " (Claude CLI waiting for input)
        val trimmed = text.trimEnd()
        if (trimmed.endsWith(">") || trimmed.endsWith("> ")) {
            lastPromptMatch.set(trimmed.takeLast(80))
        }
    }

    // ── ClaudioTestService ───────────────────────────────────────────────────

    override fun getHookServerPort()           = hookPort
    override fun getLastEventReceived()        = lastEvent.get()
    override fun getLastResponseSent()         = lastResponse.get()
    override fun getActiveDialogType()         = activeDialog.get()
    override fun getLastParsedQuestionTitle()  = lastParsedQuestion.get()
    override fun isClaudeSessionReady()        = sessionReady
    override fun getCliProcessStatus()         = cliProcessStatus
    override fun getLastTerminalPromptMatch()  = lastPromptMatch.get()
    override fun getRecentTerminalTranscript() = synchronized(transcriptLock) { transcriptBuffer.toString() }

    /**
     * Sends the JSON directly to the HookServer's HTTP endpoint - same code path as real hooks.
     * Runs on a daemon thread so Driver doesn't block waiting for HookServer to respond
     * (which may itself block waiting for a dialog on the EDT).
     */
    override fun injectHookEvent(json: String) {
        val port = hookPort
        require(port > 0) { "Hook server not started yet (port=$port)" }
        log.warn("[TEST] injecting: ${json.take(120)}")
        Thread {
            try {
                val conn = URI.create("http://127.0.0.1:$port/event").toURL().openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 5_000
                conn.readTimeout   = 30_000
                conn.doOutput      = true
                conn.outputStream.use { it.write(json.toByteArray()) }
                conn.inputStream.use { it.readBytes() }  // consume response
                conn.disconnect()
            } catch (e: Exception) {
                log.warn("[TEST] injectHookEvent failed: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
    }

    override fun injectTerminalLine(text: String) {
        val injector = terminalLineInjector
        if (injector == null) log.warn("[TEST] injectTerminalLine: no injector registered yet")
        else { log.warn("[TEST] injectTerminalLine: feeding ${text.take(80)}"); injector(text) }
    }

    override fun sendTerminalInput(text: String) {
        val sender = terminalInputSender
        if (sender == null) log.warn("[TEST] sendTerminalInput: no sender registered yet")
        else { log.warn("[TEST] sendTerminalInput: '${text.take(80)}'"); sender(text) }
    }

    override fun dismissActiveDialog() {
        val dialog = activeDialogRef.get()
        if (dialog == null) {
            log.warn("[TEST] dismissActiveDialog: no active dialog")
            return
        }
        log.warn("[TEST] dismissActiveDialog: closing ${activeDialog.get()}")
        ApplicationManager.getApplication().invokeLater { dialog.close(DialogWrapper.OK_EXIT_CODE) }
    }

    override fun answerActiveDialogWithText(text: String) {
        val dialog = activeDialogRef.get()
        if (dialog == null) {
            log.warn("[TEST] answerActiveDialogWithText: no active dialog")
            return
        }
        log.warn("[TEST] answerActiveDialogWithText: '${text.take(80)}' on ${dialog.javaClass.simpleName}")
        ApplicationManager.getApplication().invokeLater {
            when (dialog) {
                is FreeTextQuestionDialog -> dialog.setText(text)
                is AskUserQuestionDialog -> dialog.selectFreeTextAndSetText(text)
            }
            dialog.close(DialogWrapper.OK_EXIT_CODE)
        }
    }

    override fun dismissPermissionDialogWithChoice(choice: String) {
        val dialog = activeDialogRef.get()
        if (dialog == null) {
            log.warn("[TEST] dismissPermissionDialogWithChoice: no active dialog")
            return
        }
        val permChoice = PermissionChoice.valueOf(choice)
        log.warn("[TEST] dismissPermissionDialogWithChoice: $permChoice on ${dialog.javaClass.simpleName}")
        ApplicationManager.getApplication().invokeLater {
            if (dialog is PermissionDialog) {
                dialog.setChoice(permChoice)
            }
            dialog.close(DialogWrapper.OK_EXIT_CODE)
        }
    }

    override fun setTestDefaultModel(model: String) {
        defaultTestModel = model
        log.warn("[TEST] defaultTestModel=$model")
    }

    override fun clearHistory() {
        lastEvent.set(null)
        lastResponse.set(null)
        activeDialog.set(null)
        lastParsedQuestion.set(null)
        lastPromptMatch.set(null)
        synchronized(transcriptLock) { transcriptBuffer.clear() }
    }

    override fun getChangedFilePaths(): Array<String> {
        return changedFilesAccessor?.invoke()?.keys?.toTypedArray() ?: emptyArray()
    }

    override fun hasChangedFile(path: String): Boolean {
        return changedFilesAccessor?.invoke()?.containsKey(path) == true
    }

    override fun clearChangedFiles() {
        changedFilesClearer?.invoke()
    }

    override fun cancelActiveDialog() {
        val dialog = activeDialogRef.get()
        if (dialog == null) {
            log.warn("[TEST] cancelActiveDialog: no active dialog")
            return
        }
        log.warn("[TEST] cancelActiveDialog: cancelling ${activeDialog.get()}")
        SwingUtilities.invokeLater { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    companion object {
        fun getInstance(project: Project): ClaudioTestServiceImpl = project.service()
    }
}
