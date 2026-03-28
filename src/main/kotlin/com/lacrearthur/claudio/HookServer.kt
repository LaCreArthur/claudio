package com.lacrearthur.claudio

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.lacrearthur.claudio.test.ClaudioTestServiceImpl
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

private val log = Logger.getInstance("ClaudioHooks")

/**
 * HTTP server receiving structured JSON events from Claude Code hooks.
 *
 * One owner per event type:
 *   PreToolUse        -> silent policy only (no UI, returns {} to delegate or explicit allow/deny)
 *   PermissionRequest -> native approval dialog, hook response IS the decision (no keystrokes)
 *   Notification      -> lightweight status signals (no decisions)
 *   Elicitation       -> structured MCP user input (future)
 *
 * Scoped to plugin session via CLAUDIO_HOOK_PORT env var.
 */
class HookServer(
    private val project: Project,
) : Disposable {

    private var server: HttpServer? = null

    var port: Int = 0
        private set

    // Session-level allow policy: tools the user said "always allow this session"
    private val sessionAllowed = mutableSetOf<String>()

    // Project-level persistent allowlist: tools auto-allowed across sessions
    private val projectAllowlist: MutableSet<String> = loadProjectAllowlist()

    private fun allowlistFile(): File {
        val hash = project.basePath?.hashCode()?.toString(16) ?: "default"
        return File(System.getProperty("user.home"), ".claudio/allowlist/$hash.json")
    }

    private fun loadProjectAllowlist(): MutableSet<String> {
        return try {
            val file = allowlistFile()
            if (!file.exists()) return mutableSetOf()
            val text = file.readText()
            text.trim().removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
                .toMutableSet()
        } catch (_: Exception) { mutableSetOf() }
    }

    private fun saveToProjectAllowlist(toolName: String) {
        projectAllowlist.add(toolName)
        try {
            val file = allowlistFile()
            file.parentFile.mkdirs()
            file.writeText("[${projectAllowlist.joinToString(",") { "\"$it\"" }}]")
        } catch (_: Exception) {}
    }

    fun start() {
        try {
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            httpServer.executor = Executors.newFixedThreadPool(2)
            httpServer.createContext("/event") { exchange -> handleEvent(exchange) }
            httpServer.start()
            server = httpServer
            port = httpServer.address.port
            log.warn("[HOOKS] server started on port $port")
            try { project.service<ClaudioTestServiceImpl>().setHookPort(port) } catch (_: Exception) {}
        } catch (e: Exception) {
            log.error("[HOOKS] failed to start server", e)
        }
    }

    private fun handleEvent(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") { sendResponse(exchange, 405, "{}"); return }
        val body = try {
            exchange.requestBody.bufferedReader().readText()
        } catch (e: Exception) { sendResponse(exchange, 400, "{}"); return }

        val eventName = extractString(body, "hook_event_name") ?: ""
        val toolName  = extractString(body, "tool_name")
        log.warn("[HOOKS] $eventName${toolName?.let { ":$it" } ?: ""}")
        try { project.service<ClaudioTestServiceImpl>().recordEvent(body) } catch (_: Exception) {}

        try {
            val response = when (eventName) {
                "PreToolUse"        -> handlePreToolUse()
                "PermissionRequest" -> handlePermissionRequest(toolName, body)
                "Notification"      -> handleNotification(body)
                else                -> "{}"
            }
            sendResponse(exchange, 200, response)
        } catch (e: Exception) {
            log.warn("[HOOKS] error handling $eventName: ${e.message}")
            sendResponse(exchange, 200, "{}")
        }
    }

    // ── PreToolUse: silent policy only ──────────────────────────────────────
    // No UI. Returns {} to let CC apply its own rules.
    // Future: add IDE-level silent policy (block dangerous patterns, etc.)
    private fun handlePreToolUse(): String = "{}"

    // ── PermissionRequest: approval UI ──────────────────────────────────────
    // One of three paths based on tool type:
    //   INTERNAL   -> auto-allow immediately (no user decision needed)
    //   PLAN_EXIT  -> purpose-built "Start coding?" dialog
    //   REAL_TOOL  -> standard approval dialog (Bash, Write, Edit, …)
    //   UNKNOWN    -> delegate to CC's native permission handling
    //
    // Hook response IS the decision. CC processes it, terminal prompt clears.
    // No terminal keystroke replay needed for any of these paths.
    private val INTERNAL_TOOLS  = setOf("AskUserQuestion", "EnterPlanMode", "TodoWrite", "TodoRead")
    private val PLAN_EXIT_TOOLS = setOf("ExitPlanMode")
    private val REAL_TOOLS      = setOf("Bash", "Write", "Edit", "Read", "MultiEdit", "NotebookEdit", "WebSearch", "WebFetch")

    private fun handlePermissionRequest(toolName: String?, body: String): String {
        val tool = toolName ?: ""

        if (tool in INTERNAL_TOOLS) {
            log.warn("[HOOKS] auto-allow internal: $tool")
            return allow()
        }

        if (tool in PLAN_EXIT_TOOLS) {
            return handlePlanExit()
        }

        if (tool in projectAllowlist) {
            log.warn("[HOOKS] project-allow: $tool")
            return allow()
        }

        if (tool in sessionAllowed) {
            log.warn("[HOOKS] session-allow: $tool")
            return allow()
        }

        if (tool in REAL_TOOLS) {
            return showApprovalDialog(tool, body)
        }

        // Unknown tool: let CC's native permission system decide
        log.warn("[HOOKS] unknown tool '$tool' - delegating to native CC")
        return "{}"
    }

    private fun handlePlanExit(): String {
        val latch = CountDownLatch(1)
        var response = deny("Stayed in plan mode")
        SwingUtilities.invokeLater {
            try {
                val dialog = PlanExitDialog(project)
                response = if (dialog.showAndGet()) {
                    log.warn("[HOOKS] plan exit: approved")
                    allow()
                } else {
                    log.warn("[HOOKS] plan exit: cancelled")
                    deny("Stayed in plan mode")
                }
            } catch (e: Exception) {
                log.warn("[HOOKS] plan exit dialog error: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        latch.await(120, TimeUnit.SECONDS)
        return response
    }

    private fun showApprovalDialog(tool: String, body: String): String {
        val toolInput = extractObject(body, "tool_input")
        val latch = CountDownLatch(1)
        var response = deny("Cancelled in IDE")
        SwingUtilities.invokeLater {
            try {
                val request = PermissionRequest(
                    action = formatAction(tool),
                    detail = formatDetail(tool, toolInput),
                )
                val dialog = PermissionDialog(project, request)
                try { project.service<ClaudioTestServiceImpl>().setActiveDialog("permission") } catch (_: Exception) {}
                if (dialog.showAndGet()) {
                    val remember = dialog.isRememberChecked()
                    response = when (dialog.getChoice()) {
                        PermissionChoice.ALLOW_ONCE   -> {
                            if (remember) saveToProjectAllowlist(tool)
                            allow()
                        }
                        PermissionChoice.ALLOW_ALWAYS -> {
                            if (remember) saveToProjectAllowlist(tool)
                            sessionAllowed.add(tool)
                            allow()
                        }
                        PermissionChoice.DENY -> deny("Denied in IDE")
                    }
                }
                log.warn("[HOOKS] $tool -> ${dialog.getChoice()}")
            } catch (e: Exception) {
                log.warn("[HOOKS] approval dialog error: ${e.message}")
            } finally {
                try { project.service<ClaudioTestServiceImpl>().setActiveDialog(null) } catch (_: Exception) {}
                latch.countDown()
            }
        }
        latch.await(120, TimeUnit.SECONDS)
        return response
    }

    // ── Notification: observability ──────────────────────────────────────────
    private fun handleNotification(body: String): String {
        val notificationType = extractString(body, "notification_type")
        log.warn("[HOOKS] notification: $notificationType")
        return "{}"
    }

    private fun sendResponse(exchange: HttpExchange, code: Int, body: String) {
        try {
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
            if (body != "{}") {
                try { project.service<ClaudioTestServiceImpl>().recordResponse(body) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            log.warn("[HOOKS] send error: ${e.message}")
        }
    }

    override fun dispose() {
        server?.stop(0)
        server = null
        port = 0
        sessionAllowed.clear()
        log.warn("[HOOKS] server stopped")
    }

    companion object {
        private fun allow() =
            """{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}"""

        private fun deny(message: String) =
            """{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"deny","message":"$message"}}}"""

        private fun formatAction(tool: String): String = when (tool) {
            "Write"        -> "create or overwrite a file"
            "Edit"         -> "edit a file"
            "MultiEdit"    -> "edit multiple files"
            "Read"         -> "read a file"
            "Bash"         -> "run a shell command"
            "NotebookEdit" -> "edit a notebook"
            "WebSearch"    -> "search the web"
            "WebFetch"     -> "fetch a URL"
            else           -> "use $tool"
        }

        private fun formatDetail(tool: String, input: String): String = when (tool) {
            "Write", "Edit", "Read", "MultiEdit" ->
                extractString(input, "file_path") ?: input.take(300)
            "Bash" ->
                extractString(input, "command") ?: input.take(300)
            "WebSearch" ->
                extractString(input, "query") ?: input.take(300)
            "WebFetch" ->
                extractString(input, "url") ?: input.take(300)
            else -> input.take(300)
        }

        fun extractString(json: String, key: String): String? {
            val pattern = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            return pattern.find(json)?.groupValues?.get(1)
                ?.replace("\\n", "\n")
                ?.replace("\\t", "\t")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
        }

        fun extractObject(json: String, key: String): String {
            val keyIdx = json.indexOf("\"$key\"")
            if (keyIdx < 0) return "{}"
            val braceStart = json.indexOf('{', keyIdx + key.length + 2)
            if (braceStart < 0) return "{}"
            var depth = 0
            for (i in braceStart until json.length) {
                when (json[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return json.substring(braceStart, i + 1) }
                }
            }
            return "{}"
        }
    }
}
