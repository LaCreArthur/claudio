package com.lacrearthur.claudio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Integration tests for hook server lifecycle and permission dialog flows.
 *
 * Tests launch IDEA 2025.3.1 with Claudio installed, inject synthetic hook events
 * via [RemoteClaudioTestService.injectHookEvent], and assert internal plugin state
 * through that stub. No pixel guessing - all assertions are on plugin state.
 *
 * SETUP: `./gradlew buildPlugin` before running.
 * ARTIFACTS: build/test-artifacts/ (logs, screenshots on failure).
 *
 * Inside [withDriver] blocks, `this` is Driver. [RemoteClaudioTestService] is
 * pre-resolved and passed as the lambda argument.
 *
 * Dialog button clicks (Allow Once / Always Allow This Session) need Driver Finder
 * UI selectors confirmed on a live run via http://localhost:63343/api/remote-driver/
 * TODOs mark where those calls go.
 */
class ClaudioHookTest : ClaudioTestBase() {

    // ── 1. Hook server starts ────────────────────────────────────────────────

    @Test
    fun `hook server starts with valid port`() {
        var blockExecuted = false
        withDriver { svc ->
            blockExecuted = true
            val port = svc.getHookServerPort()
            assertTrue(port in 1024..65535, "Expected valid port in [1024..65535], got $port")
        }
        // If this fails, withDriver block never executed — assertions were silently swallowed.
        System.err.println("AFTER_WITHDRIVER blockExecuted=$blockExecuted")
        assertTrue(blockExecuted, "withDriver block did not execute — Driver connection failed silently")
    }

    // ── 2. Permission dialog activates for Bash ──────────────────────────────

    @Test
    fun `permission dialog becomes active for Bash PermissionRequest`() {
        withDriver { svc ->
            svc.clearHistory()

            svc.injectHookEvent(
                """{"hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{"command":"echo test"}}"""
            )

            // Plugin sets activeDialogType = "permission" when dialog is shown
            waitFor("activeDialogType == permission", timeoutMs = 10_000) {
                svc.getActiveDialogType() == "permission"
            }
            assertEquals("permission", svc.getActiveDialogType(), "Permission dialog should be active")

            // TODO(live-run): click Allow Once using Driver Finder:
            //   x("//button[@text='Allow Once']").click()    (selector to confirm at http://localhost:63343/api/remote-driver/)
            // Then assert svc.getLastResponseSent() contains "\"behavior\":\"allow\""
        }
    }

    // ── 3. Permission dialog appears for each event (before session-allow) ───

    @Test
    fun `each Bash event triggers a dialog before session allow is clicked`() {
        withDriver { svc ->
            val bashEvent =
                """{"hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{"command":"ls"}}"""

            svc.clearHistory()
            svc.injectHookEvent(bashEvent)
            waitFor("first dialog activates", timeoutMs = 10_000) {
                svc.getActiveDialogType() == "permission"
            }
            assertEquals("permission", svc.getActiveDialogType(), "Dialog should appear for first Bash event")

            // TODO(live-run): click "Always Allow This Session", inject bashEvent again,
            // assert getActiveDialogType() == null for second event (no second dialog).
        }
    }

    // ── 4. Unknown tool produces no dialog ──────────────────────────────────

    @Test
    fun `unknown tool produces no dialog and null or empty response`() {
        withDriver { svc ->
            svc.clearHistory()

            svc.injectHookEvent(
                """{"hook_event_name":"PermissionRequest","tool_name":"SomeFutureTool","tool_input":{}}"""
            )

            // Wait briefly - no dialog should appear for unknown tools
            Thread.sleep(1_000)
            assertNull(svc.getActiveDialogType(), "Unknown tool should not trigger a dialog")

            val response = svc.getLastResponseSent()
            assertTrue(response == null || response == "{}", "Unknown tool response: $response")
        }
    }

    // ── 5. PreToolUse: silent pass-through, no dialog ────────────────────────

    @Test
    fun `PreToolUse event is recorded and produces no dialog`() {
        withDriver { svc ->
            svc.clearHistory()

            svc.injectHookEvent(
                """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"ls"}}"""
            )

            Thread.sleep(1_000)
            assertNull(svc.getActiveDialogType(), "PreToolUse should not show a dialog")

            val event = svc.getLastEventReceived()
            assertNotNull(event, "PreToolUse event should be recorded")
            assertTrue(event!!.contains("PreToolUse"), "Recorded event should contain PreToolUse")

            // {} responses are not recorded (by design) - response should stay null
            assertNull(svc.getLastResponseSent(), "PreToolUse should produce no recorded response")
        }
    }

    // ── 6. Notification: no dialog, event recorded ──────────────────────────

    @Test
    fun `Notification event is recorded and produces no dialog`() {
        withDriver { svc ->
            svc.clearHistory()

            svc.injectHookEvent(
                """{"hook_event_name":"Notification","notification_type":"permission_prompt","message":"Bash wants to run: ls"}"""
            )

            Thread.sleep(1_000)
            assertNull(svc.getActiveDialogType(), "Notification should not show a dialog")

            val event = svc.getLastEventReceived()
            assertNotNull(event, "Notification event should be recorded")
            assertTrue(event!!.contains("Notification"), "Recorded event should contain Notification")
        }
    }

    // ── 7. clearHistory resets all observable state ──────────────────────────

    @Test
    fun `clearHistory resets all observable state fields`() {
        withDriver { svc ->
            // Set state via a PreToolUse event (records event, no response, no dialog)
            svc.injectHookEvent(
                """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"echo hi"}}"""
            )
            waitFor("event recorded", timeoutMs = 5_000) { svc.getLastEventReceived() != null }

            svc.clearHistory()

            assertNull(svc.getLastEventReceived(), "clearHistory should clear lastEvent")
            assertNull(svc.getLastResponseSent(), "clearHistory should clear lastResponse")
            assertNull(svc.getActiveDialogType(), "clearHistory should clear activeDialog")
        }
    }

    // ── 8. injectTerminalLine routes to CliOutputParser and detects ☐ pattern ──

    @Test
    fun `AskUserQuestion pattern is parsed and title recorded`() {
        withDriver { svc ->
            svc.clearHistory()

            // Feed multi-chunk output that CliOutputParser expects for AskUserQuestion:
            // ☐ <title> triggers capture, then numbered options, then ──── closes it
            svc.injectTerminalLine("☐ Which option do you prefer?\n1. Option A\n2. Option B\n────\n")

            waitFor("parsed question title recorded", timeoutMs = 5_000) {
                svc.getLastParsedQuestionTitle() != null
            }
            assertEquals(
                "Which option do you prefer?",
                svc.getLastParsedQuestionTitle(),
                "Parser should extract question title after ☐"
            )
        }
    }

    // ── 9. Observability: session lifecycle fields are populated ─────────────

    @Test
    fun `session lifecycle observables are populated after plugin start`() {
        withDriver { svc ->
            // isClaudeSessionReady and getCliProcessStatus are wired to TerminalView session state.
            // In test IDE the terminal reaches Running and 'claude' is sent; sessionReady becomes true.
            // We wait up to 30s - same budget as real smoke tests.
            waitFor("session ready", timeoutMs = 30_000) { svc.isClaudeSessionReady() }
            assertTrue(svc.isClaudeSessionReady(), "sessionReady should be true after terminal Running")
            assertEquals("running", svc.getCliProcessStatus(), "cliProcessStatus should be 'running'")
            // Transcript should have received at least the shell prompt from launching 'claude'
            val transcript = svc.getRecentTerminalTranscript()
            assertNotNull(transcript, "transcript must not be null")
        }
    }

    // ── 10. sendTerminalInput routes to TerminalView ─────────────────────────

    @Test
    fun `sendTerminalInput does not throw and can be called after session ready`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 30_000) { svc.isClaudeSessionReady() }
            // sendTerminalInput wires to view.createSendTextBuilder().shouldExecute().send()
            // We just verify it doesn't throw - the full send→transcript proof requires a
            // scripted shell session (Tier 1b, Cycle 2).
            svc.sendTerminalInput("# claudio-test-probe")
            Thread.sleep(500)
            // No exception = wiring is alive
        }
    }

    // ── 11. Malformed JSON does not crash the server ──────────────────────────

    @Test
    fun `malformed JSON event does not crash hook server`() {
        withDriver { svc ->
            svc.clearHistory()
            val portBefore = svc.getHookServerPort()

            svc.injectHookEvent("not valid json at all")

            Thread.sleep(1_000)
            assertEquals(portBefore, svc.getHookServerPort(), "Server port should be unchanged after malformed input")
            assertNull(svc.getActiveDialogType(), "Malformed event should not trigger any dialog")
        }
    }

    // ── 12. changedFiles Remote wiring: clearChangedFiles + getChangedFilePaths ─

    @Test
    fun `clearChangedFiles and getChangedFilePaths Remote wiring is functional`() {
        withDriver { svc ->
            svc.clearChangedFiles()

            val paths = svc.getChangedFilePaths()
            assertNotNull(paths, "getChangedFilePaths should never return null")
            assertEquals(0, paths.size, "changedFiles should be empty after clearChangedFiles")

            assertFalse(
                svc.hasChangedFile("/tmp/claudio-test-changed.txt"),
                "hasChangedFile should return false for an untracked path"
            )
        }
    }

    // ── 14. Permission Deny via DENY choice sends deny response ────────────────

    @Test
    fun `permission Deny choice sends deny response`() {
        withDriver { svc ->
            svc.clearHistory()

            svc.injectHookEvent(
                """{"hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{"command":"echo deny-test"}}"""
            )

            waitFor("permission dialog", timeoutMs = 10_000) {
                svc.getActiveDialogType() == "permission"
            }

            svc.dismissPermissionDialogWithChoice("DENY")

            waitFor("deny response recorded", timeoutMs = 10_000) {
                svc.getLastResponseSent()?.contains("\"behavior\":\"deny\"") == true
            }

            val response = svc.getLastResponseSent()!!
            assertTrue(response.contains("\"behavior\":\"deny\""),
                "Deny should send deny response. Got: ${response.take(300)}")
            assertTrue(response.contains("Denied in IDE"),
                "Deny message should say 'Denied in IDE'. Got: ${response.take(300)}")
        }
    }

    // ── 15. Permission Cancel button sends deny response ─────────────────────

    @Test
    fun `permission Cancel button sends deny response`() {
        withDriver { svc ->
            svc.clearHistory()

            svc.injectHookEvent(
                """{"hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{"command":"echo cancel-test"}}"""
            )

            waitFor("permission dialog", timeoutMs = 10_000) {
                svc.getActiveDialogType() == "permission"
            }

            svc.cancelActiveDialog()

            waitFor("cancel deny response", timeoutMs = 10_000) {
                svc.getLastResponseSent()?.contains("\"behavior\":\"deny\"") == true
            }

            val response = svc.getLastResponseSent()!!
            assertTrue(response.contains("\"behavior\":\"deny\""),
                "Cancel should send deny response. Got: ${response.take(300)}")
            assertTrue(response.contains("Cancelled in IDE"),
                "Cancel message should say 'Cancelled in IDE'. Got: ${response.take(300)}")
        }
    }

    // ── 16. Internal tool (AskUserQuestion) auto-allowed without dialog ──────

    @Test
    fun `AskUserQuestion PermissionRequest is auto-allowed without dialog`() {
        withDriver { svc ->
            svc.clearHistory()

            svc.injectHookEvent(
                """{"hook_event_name":"PermissionRequest","tool_name":"AskUserQuestion","tool_input":{"question":"test?"}}"""
            )

            // Wait for response - should be auto-allowed immediately
            waitFor("auto-allow response", timeoutMs = 10_000) {
                svc.getLastResponseSent()?.contains("\"behavior\":\"allow\"") == true
            }

            // No dialog should have appeared
            assertNull(svc.getActiveDialogType(),
                "AskUserQuestion PermissionRequest should be auto-allowed without showing a dialog")

            val response = svc.getLastResponseSent()!!
            assertTrue(response.contains("\"behavior\":\"allow\""),
                "Internal tool should produce allow response. Got: ${response.take(300)}")
        }
    }

    // ── 17. PlanExitDialog: approve sends allow response ──────────────────────

    @Test
    fun `PlanExitDialog approve sends allow response`() {
        withDriver { svc ->
            svc.clearHistory()

            svc.injectHookEvent(
                """{"hook_event_name":"PermissionRequest","tool_name":"ExitPlanMode","tool_input":{}}"""
            )

            waitFor("planExit dialog", timeoutMs = 10_000) {
                svc.getActiveDialogType() == "planExit"
            }

            // OK = "Start Coding" = approve plan exit
            svc.dismissActiveDialog()

            waitFor("allow response", timeoutMs = 10_000) {
                svc.getLastResponseSent()?.contains("\"behavior\":\"allow\"") == true
            }

            val response = svc.getLastResponseSent()!!
            assertTrue(response.contains("\"behavior\":\"allow\""),
                "Approving plan exit should send allow. Got: ${response.take(300)}")
        }
    }

    // ── 18. PlanExitDialog: reject (Keep Planning) sends deny response ───────

    @Test
    fun `PlanExitDialog reject sends deny response`() {
        withDriver { svc ->
            svc.clearHistory()

            svc.injectHookEvent(
                """{"hook_event_name":"PermissionRequest","tool_name":"ExitPlanMode","tool_input":{}}"""
            )

            waitFor("planExit dialog", timeoutMs = 10_000) {
                svc.getActiveDialogType() == "planExit"
            }

            // Cancel = "Keep Planning" = reject plan exit
            svc.cancelActiveDialog()

            waitFor("deny response", timeoutMs = 10_000) {
                svc.getLastResponseSent()?.contains("\"behavior\":\"deny\"") == true
            }

            val response = svc.getLastResponseSent()!!
            assertTrue(response.contains("\"behavior\":\"deny\""),
                "Rejecting plan exit should send deny. Got: ${response.take(300)}")
            assertTrue(response.contains("Stayed in plan mode"),
                "Reject message should say 'Stayed in plan mode'. Got: ${response.take(300)}")
        }
    }

    // ── 19. Empty JSON event doesn't crash hook server ───────────────────────

    @Test
    fun `empty JSON event does not crash hook server`() {
        withDriver { svc ->
            svc.clearHistory()
            val portBefore = svc.getHookServerPort()

            svc.injectHookEvent("{}")

            Thread.sleep(1_000)
            assertEquals(portBefore, svc.getHookServerPort(), "Server port should be unchanged after empty JSON")
            assertNull(svc.getActiveDialogType(), "Empty JSON should not trigger any dialog")
        }
    }

    // ── 20. Rapid sequential permission events don't corrupt state ────────────

    @Test
    fun `rapid sequential permission events are handled without corruption`() {
        withDriver { svc ->
            svc.clearHistory()

            // Fire 3 events in quick succession - each should queue, not crash
            val tools = listOf("Bash", "Write", "Edit")
            for (tool in tools) {
                svc.injectHookEvent(
                    """{"hook_event_name":"PermissionRequest","tool_name":"$tool","tool_input":{}}"""
                )
                Thread.sleep(100) // small gap to avoid HTTP connection issues
            }

            // First dialog should appear
            waitFor("first permission dialog", timeoutMs = 15_000) {
                svc.getActiveDialogType() == "permission"
            }
            assertEquals("permission", svc.getActiveDialogType())

            // Dismiss first - the rest are queued on the HookServer thread pool
            svc.dismissActiveDialog()

            waitFor("first dialog dismissed", timeoutMs = 15_000) {
                svc.getActiveDialogType() == null || svc.getActiveDialogType() == "permission"
            }

            // Server should still be alive
            assertTrue(svc.getHookServerPort() in 1024..65535,
                "Hook server should still be running after rapid events")
        }
    }

    // ── 21. Permission dialog shows for different REAL_TOOL types ─────────────

    @Test
    fun `permission dialog appears for Write tool PermissionRequest`() {
        withDriver { svc ->
            svc.clearHistory()

            svc.injectHookEvent(
                """{"hook_event_name":"PermissionRequest","tool_name":"Write","tool_input":{"file_path":"/tmp/test.txt","content":"test"}}"""
            )

            waitFor("permission dialog for Write", timeoutMs = 10_000) {
                svc.getActiveDialogType() == "permission"
            }
            assertEquals("permission", svc.getActiveDialogType(),
                "Write tool should trigger permission dialog")

            // Verify the event was recorded with Write tool
            val event = svc.getLastEventReceived()
            assertNotNull(event)
            assertTrue(event!!.contains("Write"))
        }
    }

    // ── 13. PreToolUse Write event is accepted silently (no dialog, event recorded) ─

    @Test
    fun `PreToolUse Write event is accepted without triggering a dialog`() {
        withDriver { svc ->
            svc.clearHistory()
            svc.clearChangedFiles()

            svc.injectHookEvent(
                """{"hook_event_name":"PreToolUse","tool_name":"Write","tool_input":{"file_path":"/tmp/claudio-test-changed.txt","content":"new content"}}"""
            )

            Thread.sleep(1_000)

            // PreToolUse for Write is a silent pass-through - no dialog should appear
            assertNull(svc.getActiveDialogType(), "PreToolUse Write should not show any dialog")

            // Event should be recorded
            val event = svc.getLastEventReceived()
            assertNotNull(event, "PreToolUse Write event should be recorded in history")
            assertTrue(event!!.contains("Write"), "Recorded event should reference tool_name Write")

            // File is now in pendingSnapshots (awaiting VFS change event), not yet changedFiles
            // VFS events originate inside the IDE process and cannot be triggered from the test process,
            // so we verify the pre-condition: changedFiles remains empty at this stage.
            assertEquals(
                0,
                svc.getChangedFilePaths().size,
                "changedFiles should be empty before a VFS event arrives (file is only in pendingSnapshots)"
            )
        }
    }
}
