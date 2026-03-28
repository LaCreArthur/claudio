package com.lacrearthur.claudio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Real E2E tests: proves the real Claude CLI + plugin stack is alive.
 *
 * Requirements:
 *   - `claude` CLI installed and authenticated on the host machine
 *   - Plugin built (./gradlew buildPlugin)
 *
 * Tagged @Tag("realE2E") so it is excluded from the fast `integrationTest` task.
 * Run with: ./gradlew realE2ETest
 *
 * Important: global permissions.allow in settings.json auto-allows tools (Bash, Edit, etc.),
 * so PermissionRequest hooks won't fire for naturally triggered tool use. Tests that need
 * permission dialogs inject events directly to the hook server HTTP endpoint instead.
 * Users without global allow-lists will see the same dialog triggered naturally by Claude.
 */
@Tag("realE2E")
class RealClaudeSmokeTest : ClaudioTestBase() {

    @Test
    fun `session starts and becomes ready`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            assertTrue(svc.isClaudeSessionReady(), "Claude session should be ready after terminal starts")
            assertEquals("running", svc.getCliProcessStatus(), "CLI process should report 'running'")
            assertTrue(svc.getHookServerPort() in 1024..65535, "Hook server should be listening on a valid port")
        }
    }

    // T2 - Permission dialog flow via injected PermissionRequest.
    // Global permissions.allow auto-allows Bash, so we can't rely on Claude naturally
    // triggering PermissionRequest. Inject directly to the hook server HTTP endpoint
    // (same code path as real hooks). Fresh temp project = empty allowlists.
    @Test
    fun `permission dialog appears for PermissionRequest hook event`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            val permissionEvent = """
                {"hook_event_name":"PermissionRequest","tool_name":"Bash",
                 "tool_input":{"command":"echo claudio-smoke-test"}}
            """.trimIndent()
            svc.injectHookEvent(permissionEvent)

            waitFor("permission dialog", timeoutMs = 15_000) {
                svc.getActiveDialogType() == "permission"
            }
            assertEquals("permission", svc.getActiveDialogType(),
                "Injected PermissionRequest should trigger native permission dialog")
        }
    }

    // T4 - AskUserQuestion: parser detects ☐ prompt, dialog appears, dismiss works.
    // Claude uses AskUserQuestion -> terminal renders ☐ -> parser detects -> dialog shows.
    // We dismiss the dialog (default option 0) to verify the dismiss mechanism works.
    // We don't assert Claude's response content - that's model-dependent and flaky.
    @Test
    fun `AskUserQuestion dialog appears and default option is sent`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            svc.sendTerminalInput("Use the AskUserQuestion tool with question 'Pick one' and options 'Alpha', 'Beta'\n")

            // Wait for parser to detect the ☐ prompt
            waitFor("AskUserQuestion parsed", timeoutMs = 120_000) {
                svc.getLastParsedQuestionTitle() != null
            }
            assertNotNull(svc.getLastParsedQuestionTitle(), "Parser should detect AskUserQuestion ☐ prompt")

            // Wait for dialog to appear
            waitFor("askUserQuestion dialog", timeoutMs = 30_000) {
                svc.getActiveDialogType() == "askUserQuestion"
            }
            assertEquals("askUserQuestion", svc.getActiveDialogType())

            // Dismiss dialog - accepts default option (index 0)
            svc.dismissActiveDialog()

            // Verify dialog was dismissed (type resets to null)
            waitFor("dialog dismissed", timeoutMs = 5_000) {
                svc.getActiveDialogType() == null
            }
        }
    }

    // T3 - send a message and get a response
    // Acceptance scenario: send "say exactly: claudio-test-ok" → transcript contains
    //   "claudio-test-ok" from claude's response (not from the terminal echo of our input).
    //
    // Echo trap: terminals echo typed input back immediately. The echo appears within ~100ms.
    // Claude takes ~2-3s to respond. Also, the terminal wraps long lines, so
    // substringAfter("say exactly: claudio-test-ok") fails to find the echo boundary
    // when it's split across lines with trailing spaces.
    //
    // Fix: clearHistory() after 1s (echo is in buffer, response is not yet) → clear echo
    //   → wait for response to appear in the now-clean transcript.
    //
    // Uses default model (configure claude CLI default to Haiku for cost efficiency).
    @Test
    fun `send message and get response`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            svc.sendTerminalInput("say exactly: claudio-test-ok\n")
            // Let the echo arrive (~100ms) and settle; claude takes ~2s+ to respond
            Thread.sleep(1_000)
            svc.clearHistory()  // Discard echo; only claude's response will appear next

            waitFor("claude responded", timeoutMs = 120_000) {
                svc.getRecentTerminalTranscript().contains("claudio-test-ok")
            }
            val transcript = svc.getRecentTerminalTranscript()
            assertTrue(
                transcript.contains("claudio-test-ok"),
                "Probe not in transcript after echo cleared. Transcript: ${transcript.take(500)}"
            )
        }
    }
}
