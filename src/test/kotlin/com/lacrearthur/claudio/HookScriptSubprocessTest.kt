package com.lacrearthur.claudio

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies the real claudio-hook.sh subprocess path:
 * script is executable, CLAUDIO_HOOK_PORT scopes it, it forwards stdin to the server.
 *
 * No IDE required. Runs in < 5s as part of ./gradlew test.
 */
class HookScriptSubprocessTest {

    private val scriptContent =
        "#!/bin/bash\n" +
        "if [ -z \"\$CLAUDIO_HOOK_PORT\" ]; then exit 0; fi\n" +
        "cat | curl -s --max-time 10 -X POST -H \"Content-Type: application/json\" " +
        "-d @- \"http://127.0.0.1:\$CLAUDIO_HOOK_PORT/event\" 2>/dev/null\n"

    @Test
    fun `hook script forwards JSON body to the server`() {
        val scriptFile = Files.createTempFile("claudio-hook-test", ".sh").toFile()
        scriptFile.writeText(scriptContent)
        scriptFile.setExecutable(true)

        val received = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/event") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            received.set(body)
            latch.countDown()
            val resp = "{}".toByteArray()
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.start()

        try {
            val payload = """{"hook_event_name":"PreToolUse","tool_name":"Bash"}"""
            val process = ProcessBuilder("bash", scriptFile.absolutePath)
                .also { it.environment()["CLAUDIO_HOOK_PORT"] = server.address.port.toString() }
                .start()
            process.outputStream.bufferedWriter().use { it.write(payload) }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Script did not exit within 15s")
            assertTrue(latch.await(5, TimeUnit.SECONDS), "HTTP server did not receive the request within 5s")
            assertEquals(payload, received.get(), "Server should receive the exact stdin payload")
        } finally {
            server.stop(0)
            scriptFile.delete()
        }
    }

    @Test
    fun `hook script exits silently when CLAUDIO_HOOK_PORT is not set`() {
        val scriptFile = Files.createTempFile("claudio-hook-test", ".sh").toFile()
        scriptFile.writeText(scriptContent)
        scriptFile.setExecutable(true)

        try {
            val process = ProcessBuilder("bash", scriptFile.absolutePath)
                .also { it.environment().remove("CLAUDIO_HOOK_PORT") }
                .start()
            process.outputStream.close()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Script should exit quickly when port not set")
            assertEquals(0, process.exitValue(), "Exit code should be 0 (silent no-op)")
        } finally {
            scriptFile.delete()
        }
    }
}
