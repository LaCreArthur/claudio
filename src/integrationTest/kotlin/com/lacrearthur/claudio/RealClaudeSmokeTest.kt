package com.lacrearthur.claudio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier 1 smoke test: proves the real Claude CLI + plugin stack is alive.
 *
 * Requirements:
 *   - `claude` CLI installed and authenticated on the host machine
 *   - Plugin built (./gradlew buildPlugin)
 *
 * Tagged @Tag("realE2E") so it is excluded from the fast `integrationTest` task.
 * Run with: ./gradlew realE2ETest
 *
 * Two scenarios only - earn more by pain:
 *   1. Session starts and becomes ready (terminal Running + hook server alive)
 *   2. Send a Bash prompt -> real PermissionRequest hook fires -> native dialog appears
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

    @Test
    fun `Bash permission dialog appears after prompt`() {
        withDriver { svc ->
            waitFor("session ready", timeoutMs = 60_000) { svc.isClaudeSessionReady() }
            svc.clearHistory()

            // Explicit bash instruction - reliably triggers PermissionRequest hook
            svc.sendTerminalInput("Use bash to run: echo claudio-smoke-test\n")

            waitFor("Bash permission dialog", timeoutMs = 90_000) {
                svc.getActiveDialogType() == "permission"
            }
            assertEquals("permission", svc.getActiveDialogType(),
                "Real Bash PermissionRequest hook should trigger native permission dialog")
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
