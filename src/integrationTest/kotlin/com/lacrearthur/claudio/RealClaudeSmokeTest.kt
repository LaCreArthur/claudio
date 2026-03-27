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
}
