package com.lacrearthur.claudio

import com.intellij.driver.client.Remote

/**
 * Remote stub for [com.lacrearthur.claudio.test.ClaudioTestServiceImpl].
 * Declared in test source so the @Remote annotation (a Driver SDK dep) never
 * pollutes production code. Driver proxies calls across the test/IDE process boundary.
 */
@Remote("com.lacrearthur.claudio.test.ClaudioTestServiceImpl", plugin = "com.lacrearthur.claudio")
interface RemoteClaudioTestService {
    fun getHookServerPort(): Int
    fun getLastEventReceived(): String?
    fun getLastResponseSent(): String?
    fun getActiveDialogType(): String?
    fun injectHookEvent(json: String)
    fun injectTerminalLine(text: String)
    fun getLastParsedQuestionTitle(): String?
    fun isClaudeSessionReady(): Boolean
    fun getCliProcessStatus(): String
    fun getRecentTerminalTranscript(): String
    fun getLastTerminalPromptMatch(): String?
    fun sendTerminalInput(text: String)
    fun dismissActiveDialog()
    fun answerActiveDialogWithText(text: String)
    fun dismissPermissionDialogWithChoice(choice: String)
    fun setTestDefaultModel(model: String)
    fun clearHistory()
    fun getChangedFilePaths(): Array<String>
    fun hasChangedFile(path: String): Boolean
    fun clearChangedFiles()
    fun cancelActiveDialog()
}
