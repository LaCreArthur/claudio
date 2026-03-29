package com.lacrearthur.claudio.test

/**
 * Exposes internal plugin state to JetBrains Driver API stubs during integration tests.
 * Registered as a project service so Driver can reach it across the test/IDE process boundary.
 *
 * In production, the service exists but is never called from outside the IDE process.
 */
interface ClaudioTestService {
    /** Port the HookServer is listening on. 0 if not yet started. */
    fun getHookServerPort(): Int

    /** Raw JSON body of the last hook event received by HookServer. */
    fun getLastEventReceived(): String?

    /** Raw JSON body of the last hook response sent by HookServer. */
    fun getLastResponseSent(): String?

    /** "permission" | "ask-user" | null - which native dialog is currently visible. */
    fun getActiveDialogType(): String?

    /**
     * Injects a synthetic hook event as if the claudio-hook.sh script sent it.
     * Sends an HTTP POST to http://127.0.0.1:{hookPort}/event - same path as real hooks.
     */
    fun injectHookEvent(json: String)

    /** Feeds a line of text through CliOutputParser - for testing AskUserQuestion detection. */
    fun injectTerminalLine(text: String)

    /** Title of the last ParsedQuestion fired by CliOutputParser. Null if none yet. */
    fun getLastParsedQuestionTitle(): String?

    /** True once TerminalViewSessionState.Running is reached and Claude CLI command is sent. */
    fun isClaudeSessionReady(): Boolean

    /** "not_started" | "running" | "exited(N)" - lifecycle of the terminal session. */
    fun getCliProcessStatus(): String

    /** Rolling ~1000-char ring buffer of raw terminal output text. */
    fun getRecentTerminalTranscript(): String

    /** Last line of terminal output that matched a Claude prompt-ready pattern (e.g. "> "). Null if none yet. */
    fun getLastTerminalPromptMatch(): String?

    /** Sends text to the real TerminalView as if typed from the input bar. */
    fun sendTerminalInput(text: String)

    /** Programmatically dismiss the active dialog (OK/accept). For test use only. */
    fun dismissActiveDialog()

    /** Set text in a FreeTextQuestionDialog and close with OK. For test use only. */
    fun answerActiveDialogWithText(text: String)

    /** Set PermissionChoice on active PermissionDialog and close with OK. Values: "ALLOW_ONCE", "ALLOW_ALWAYS", "DENY". */
    fun dismissPermissionDialogWithChoice(choice: String)

    /** Set the model to use when launching claude. Must be called before tool window opens. */
    fun setTestDefaultModel(model: String)

    /** Resets all observable state to null/empty. Does NOT reset live session state. */
    fun clearHistory()
}
