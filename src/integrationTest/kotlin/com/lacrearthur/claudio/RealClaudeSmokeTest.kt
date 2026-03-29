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

    // T5 - AskUserQuestion: custom free-text answer.
    // Asks Claude to use AskUserQuestion with a "Type something else" option (triggers isFreeText).
    // Test selects the free-text option, types custom text, and verifies Claude receives it.
    @Test
    fun `AskUserQuestion free-text answer is sent to Claude`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            svc.sendTerminalInput("Use the AskUserQuestion tool with question 'Pick a color' and options 'Red', 'Blue', 'Type something else'\n")

            // Wait for parser to detect the ☐ prompt
            waitFor("AskUserQuestion parsed", timeoutMs = 120_000) {
                svc.getLastParsedQuestionTitle() != null
            }

            // Wait for dialog to appear
            waitFor("askUserQuestion dialog", timeoutMs = 30_000) {
                svc.getActiveDialogType() == "askUserQuestion"
            }

            // Select free-text option and type custom answer
            svc.answerActiveDialogWithText("custom-answer-xyz")

            // Verify dialog was dismissed
            waitFor("dialog dismissed", timeoutMs = 5_000) {
                svc.getActiveDialogType() == null
            }

            // Clear echo and wait for Claude to acknowledge our answer
            Thread.sleep(1_000)
            svc.clearHistory()

            waitFor("claude acknowledged free-text", timeoutMs = 120_000) {
                svc.getRecentTerminalTranscript().contains("custom-answer-xyz")
            }
            assertTrue(
                svc.getRecentTerminalTranscript().contains("custom-answer-xyz"),
                "Claude should echo back the free-text answer. Transcript: ${svc.getRecentTerminalTranscript().take(500)}"
            )
        }
    }

    // T9 - Permission dialog: Always Allow This Session grants session-level auto-allow.
    // First injection: dialog appears, choose ALLOW_ALWAYS, close.
    // Second injection: no dialog (tool is now session-allowed, auto-allowed silently).
    @Test
    fun `permission Always Allow This Session auto-allows subsequent requests`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            val permissionEvent = """
                {"hook_event_name":"PermissionRequest","tool_name":"Bash",
                 "tool_input":{"command":"echo session-allow-test"}}
            """.trimIndent()

            // First injection: dialog appears, choose Always Allow
            svc.injectHookEvent(permissionEvent)

            waitFor("permission dialog", timeoutMs = 15_000) {
                svc.getActiveDialogType() == "permission"
            }

            svc.dismissPermissionDialogWithChoice("ALLOW_ALWAYS")

            // Wait for response and dialog to close
            waitFor("allow response recorded", timeoutMs = 10_000) {
                svc.getLastResponseSent()?.contains("\"behavior\":\"allow\"") == true
            }
            waitFor("dialog dismissed", timeoutMs = 5_000) {
                svc.getActiveDialogType() == null
            }

            // Reset observable state for second injection
            svc.clearHistory()

            // Second injection: should be auto-allowed (no dialog)
            svc.injectHookEvent(permissionEvent)

            // Wait for the response to come back (auto-allowed, no dialog)
            waitFor("auto-allow response", timeoutMs = 10_000) {
                svc.getLastResponseSent()?.contains("\"behavior\":\"allow\"") == true
            }

            // Verify no dialog appeared - activeDialogType should still be null
            assertNull(svc.getActiveDialogType(),
                "Second PermissionRequest should be auto-allowed without showing a dialog")
        }
    }

    // T8 - Permission dialog: Allow Once sends allow response but does NOT grant session-allow.
    // Inject PermissionRequest → dialog → dismiss (default = Allow Once) → verify response.
    // Inject a second PermissionRequest → dialog must appear again (not session-allowed).
    @Test
    fun `permission Allow Once sends allow and does not session-allow`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            val permissionEvent = """
                {"hook_event_name":"PermissionRequest","tool_name":"Bash",
                 "tool_input":{"command":"echo allow-once-test"}}
            """.trimIndent()

            // First injection: dialog appears, dismiss with default (Allow Once)
            svc.injectHookEvent(permissionEvent)

            waitFor("permission dialog", timeoutMs = 15_000) {
                svc.getActiveDialogType() == "permission"
            }

            svc.dismissActiveDialog()

            // Wait for response to be recorded (hook handler completes after dialog closes)
            waitFor("allow response recorded", timeoutMs = 10_000) {
                svc.getLastResponseSent()?.contains("\"behavior\":\"allow\"") == true
            }
            val response = svc.getLastResponseSent()!!
            assertTrue(response.contains("\"behavior\":\"allow\""),
                "Allow Once should send allow response. Got: ${response.take(300)}")

            // Reset observable state for second injection
            svc.clearHistory()

            // Second injection: dialog must appear again (Allow Once != session-allow)
            svc.injectHookEvent(permissionEvent)

            waitFor("second permission dialog", timeoutMs = 15_000) {
                svc.getActiveDialogType() == "permission"
            }
            assertEquals("permission", svc.getActiveDialogType(),
                "Second PermissionRequest should show dialog again (Allow Once does not grant session-allow)")
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
