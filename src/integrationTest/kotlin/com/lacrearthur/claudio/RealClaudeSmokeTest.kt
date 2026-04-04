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
            waitFor("dialog dismissed", timeoutMs = 15_000) {
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

            // Select free-text option and type custom answer.
            // Retry dismiss if subscription popup blocks EDT (UX-001).
            svc.answerActiveDialogWithText("custom-answer-xyz")

            // Wait for dialog to close - retry if blocked by overlay popup
            var dismissed = false
            for (attempt in 1..3) {
                try {
                    waitFor("dialog dismissed (attempt $attempt)", timeoutMs = 10_000) {
                        svc.getActiveDialogType() == null
                    }
                    dismissed = true
                    break
                } catch (_: Exception) {
                    // Retry: re-send close in case popup blocked the first attempt
                    svc.dismissActiveDialog()
                }
            }
            assertTrue(dismissed, "Dialog should be dismissed after retries")

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
            waitFor("dialog dismissed", timeoutMs = 15_000) {
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

    // T10 - Permission Deny: inject PermissionRequest during real session, deny it.
    // Same flow as T8 (Allow Once) but with DENY choice.
    // Verifies the deny response is properly sent back to the CLI.
    @Test
    fun `permission Deny sends deny response during real session`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            val permissionEvent = """
                {"hook_event_name":"PermissionRequest","tool_name":"Bash",
                 "tool_input":{"command":"echo deny-real-test"}}
            """.trimIndent()

            svc.injectHookEvent(permissionEvent)

            waitFor("permission dialog", timeoutMs = 15_000) {
                svc.getActiveDialogType() == "permission"
            }

            svc.dismissPermissionDialogWithChoice("DENY")

            waitFor("deny response recorded", timeoutMs = 10_000) {
                svc.getLastResponseSent()?.contains("\"behavior\":\"deny\"") == true
            }

            val response = svc.getLastResponseSent()!!
            assertTrue(response.contains("\"behavior\":\"deny\""),
                "Deny should produce deny response. Got: ${response.take(300)}")

            // Dialog should be dismissed
            waitFor("dialog dismissed", timeoutMs = 15_000) {
                svc.getActiveDialogType() == null
            }
        }
    }

    // T11 - Multi-turn conversation: send two messages, verify both get responses.
    // Proves the plugin maintains a working session across multiple user interactions.
    @Test
    fun `multi-turn conversation gets responses for each message`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            // First message
            svc.sendTerminalInput("say exactly: first-turn-ok\n")
            Thread.sleep(1_000)
            svc.clearHistory()

            waitFor("first response", timeoutMs = 120_000) {
                svc.getRecentTerminalTranscript().contains("first-turn-ok")
            }
            assertTrue(svc.getRecentTerminalTranscript().contains("first-turn-ok"),
                "First turn probe not found. Transcript: ${svc.getRecentTerminalTranscript().take(500)}")

            // Second message - new conversation turn
            svc.clearHistory()
            svc.sendTerminalInput("say exactly: second-turn-ok\n")
            Thread.sleep(1_000)
            svc.clearHistory()

            waitFor("second response", timeoutMs = 120_000) {
                svc.getRecentTerminalTranscript().contains("second-turn-ok")
            }
            assertTrue(svc.getRecentTerminalTranscript().contains("second-turn-ok"),
                "Second turn probe not found. Transcript: ${svc.getRecentTerminalTranscript().take(500)}")
        }
    }

    // T12 - /clear slash command: verify plugin handles clear and session remains usable.
    // /clear is a local CLI command. We verify the command is accepted and the session
    // still responds to a follow-up message after clearing.
    @Test
    fun `slash clear resets conversation and session remains usable`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            // Send /clear - local CLI command, fast response
            svc.sendTerminalInput("/clear\n")

            // Don't clearHistory before waitFor - /clear responds fast (~500ms)
            waitFor("clear confirmed", timeoutMs = 30_000) {
                val t = svc.getRecentTerminalTranscript()
                t.contains("clear", ignoreCase = true) || t.contains("conversation", ignoreCase = true)
            }

            // Verify session still works after clear - send a probe
            svc.clearHistory()
            svc.sendTerminalInput("say exactly: post-clear-ok\n")
            Thread.sleep(1_000)
            svc.clearHistory()

            waitFor("response after clear", timeoutMs = 120_000) {
                svc.getRecentTerminalTranscript().contains("post-clear-ok")
            }
            assertTrue(svc.getRecentTerminalTranscript().contains("post-clear-ok"),
                "Session should still respond after /clear. Transcript: ${svc.getRecentTerminalTranscript().take(500)}")
        }
    }

    // T13 - /cost slash command: verify plugin forwards and displays cost info.
    // /cost is a local CLI command, response is fast.
    @Test
    fun `slash cost returns session cost info`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            svc.sendTerminalInput("/cost\n")

            // /cost returns immediately with cost breakdown
            waitFor("cost info displayed", timeoutMs = 30_000) {
                val t = svc.getRecentTerminalTranscript()
                t.contains("cost", ignoreCase = true) || t.contains("token", ignoreCase = true)
                    || t.contains("$", ignoreCase = false)
            }
            // No assertion on specific values - just verify the command works
        }
    }

    // T14 - Session remains usable after permission deny.
    // Deny a permission request, then send a normal message to verify the session didn't break.
    @Test
    fun `session remains usable after permission deny`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            // Inject and deny a permission request
            svc.injectHookEvent(
                """{"hook_event_name":"PermissionRequest","tool_name":"Bash",
                 "tool_input":{"command":"echo session-stability-test"}}""".trimIndent()
            )

            waitFor("permission dialog", timeoutMs = 15_000) {
                svc.getActiveDialogType() == "permission"
            }

            svc.dismissPermissionDialogWithChoice("DENY")

            waitFor("deny response", timeoutMs = 10_000) {
                svc.getLastResponseSent()?.contains("\"behavior\":\"deny\"") == true
            }

            waitFor("dialog dismissed", timeoutMs = 15_000) {
                svc.getActiveDialogType() == null
            }

            // Now verify the session is still responsive
            svc.clearHistory()
            svc.sendTerminalInput("say exactly: still-alive-ok\n")
            Thread.sleep(1_000)
            svc.clearHistory()

            waitFor("response after deny", timeoutMs = 120_000) {
                svc.getRecentTerminalTranscript().contains("still-alive-ok")
            }
            assertTrue(svc.getRecentTerminalTranscript().contains("still-alive-ok"),
                "Session should still respond after permission deny. Transcript: ${svc.getRecentTerminalTranscript().take(500)}")
        }
    }

    // T6 - Model switch to Sonnet then probe response.
    // Switches from default (Haiku) to Sonnet via /model command, waits for confirmation,
    // then sends a probe to verify the session is live on Sonnet.
    // Only test that uses Sonnet - cost is justified to verify model switching works.
    @Test
    fun `model switch to sonnet and response received`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            // Switch model - /model is a local CLI command, response arrives fast
            svc.sendTerminalInput("/model sonnet\n")

            // Don't clearHistory here - the confirmation arrives within ~500ms
            // and clearHistory would wipe it before waitFor reads it
            waitFor("model switch confirmed", timeoutMs = 30_000) {
                val t = svc.getRecentTerminalTranscript()
                t.contains("sonnet", ignoreCase = true) || t.contains("model", ignoreCase = true)
            }

            // Now probe Sonnet
            svc.clearHistory()
            svc.sendTerminalInput("say exactly: sonnet-test-ok\n")
            Thread.sleep(1_000)
            svc.clearHistory()

            waitFor("claude responded", timeoutMs = 120_000) {
                svc.getRecentTerminalTranscript().contains("sonnet-test-ok")
            }
            val transcript = svc.getRecentTerminalTranscript()
            assertTrue(
                transcript.contains("sonnet-test-ok"),
                "Probe not in transcript after model switch to Sonnet. Transcript: ${transcript.take(500)}"
            )
        }
    }
}
