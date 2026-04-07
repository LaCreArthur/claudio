package com.lacrearthur.claudio

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.HttpRequestHandler

private val log = Logger.getInstance("ClaudioIdeMcp")

/**
 * MCP server exposing IDE capabilities to the Claude Code CLI.
 *
 * Uses IntelliJ's built-in HTTP server (Netty) for WebSocket connections.
 * The CLI discovers this via ~/.claude/ide/<port>.lock and connects to
 * ws://127.0.0.1:<builtInPort>/api/claudio/mcp
 */
class IdeMcpServer(
    private val project: Project,
) : Disposable {

    private var builtInPort: Int = 0

    val port: Int get() = builtInPort

    fun start() {
        try {
            val manager = BuiltInServerManager.getInstance()
            // waitForStart() asserts NOT on EDT. When called from ClaudePanel init (EDT),
            // use port directly (server starts early in IDE lifecycle, usually ready).
            if (ApplicationManager.getApplication().isDispatchThread) {
                builtInPort = try { manager.port } catch (_: Exception) { 0 }
            } else {
                manager.waitForStart()
                builtInPort = manager.port
            }
            if (builtInPort <= 0) {
                log.warn("[MCP] built-in server not ready yet, port=$builtInPort")
                return
            }
            val handler = HttpRequestHandler.EP_NAME.extensionList
                .filterIsInstance<IdeWebSocketHandler>()
                .firstOrNull()
            if (handler != null) {
                handler.onMessage = ::handleMessage
                log.warn("[MCP] using built-in server on port $builtInPort")
            } else {
                log.warn("[MCP] IdeWebSocketHandler not found in extension list")
            }
        } catch (e: Exception) {
            log.warn("[MCP] built-in server unavailable: ${e.message}")
        }
    }

    private fun handleMessage(message: String): String? {
        val root = try {
            JsonParser.parseString(message).asJsonObject
        } catch (e: Exception) {
            log.warn("[MCP] invalid JSON: ${e.message}")
            return null
        }

        val method = root.get("method")?.asString
        val id = root.get("id")?.asInt

        // Notifications (no id) - don't respond
        if (id == null) {
            log.warn("[MCP] notification: $method")
            return null
        }

        log.warn("[MCP] request: $method (id=$id)")
        return try {
            when (method) {
                "initialize" -> handleInitialize(id, root)
                "tools/list" -> handleToolsList(id)
                "tools/call" -> handleToolsCall(id, root)
                "resources/list" -> handleResourcesList(id)
                "resources/read" -> handleResourcesRead(id, root)
                else -> jsonRpcError(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            log.warn("[MCP] error handling $method: ${e.message}")
            jsonRpcError(id, -32603, "Internal error: ${e.message}")
        }
    }

    // ── initialize ──────────────────────────────────────────────────────

    private fun handleInitialize(id: Int, root: JsonObject): String {
        val params = root.getAsJsonObject("params") ?: JsonObject()
        val clientVersion = params.get("protocolVersion")?.asString ?: "2024-11-05"
        val result = JsonObject().apply {
            addProperty("protocolVersion", clientVersion)
            add("serverInfo", JsonObject().apply {
                addProperty("name", "claudio-ide")
                addProperty("version", "0.1.0")
            })
            add("capabilities", JsonObject().apply {
                add("tools", JsonObject().apply { addProperty("listChanged", false) })
                add("resources", JsonObject().apply {
                    addProperty("subscribe", false)
                    addProperty("listChanged", false)
                })
            })
        }
        return jsonRpcResult(id, result)
    }

    // ── tools/list ──────────────────────────────────────────────────────

    private fun handleToolsList(id: Int): String {
        val tools = JsonArray().apply {
            add(mcpTool("getDiagnostics", "Get compiler errors and warnings from the IDE for the current file or a specified file path",
                """{"type":"object","properties":{"filePath":{"type":"string","description":"Absolute file path. If omitted, uses the currently active editor."}},"required":[]}"""))
            add(mcpTool("getSelection", "Get the current editor selection text, file path, and line range",
                """{"type":"object","properties":{},"required":[]}"""))
            add(mcpTool("getOpenFiles", "Get the list of currently open editor tabs with file paths",
                """{"type":"object","properties":{},"required":[]}"""))
            add(mcpTool("showDiff", "Open the IDE's native diff viewer to compare original and modified file content",
                """{"type":"object","properties":{"filePath":{"type":"string","description":"Absolute file path"},"before":{"type":"string","description":"Original file content"},"after":{"type":"string","description":"Modified file content"},"title":{"type":"string","description":"Diff viewer title (optional)"}},"required":["filePath","before","after"]}"""))
        }
        val result = JsonObject().apply { add("tools", tools) }
        return jsonRpcResult(id, result)
    }

    // ── tools/call ──────────────────────────────────────────────────────

    private fun handleToolsCall(id: Int, root: JsonObject): String {
        val params = root.getAsJsonObject("params") ?: JsonObject()
        val toolName = params.get("name")?.asString ?: return jsonRpcError(id, -32602, "Missing tool name")
        val arguments = params.getAsJsonObject("arguments") ?: JsonObject()

        val content = when (toolName) {
            "getDiagnostics" -> callGetDiagnostics(arguments)
            "getSelection" -> callGetSelection()
            "getOpenFiles" -> callGetOpenFiles()
            "showDiff" -> callShowDiff(arguments)
            else -> return jsonRpcError(id, -32602, "Unknown tool: $toolName")
        }

        val result = JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", content)
                })
            })
        }
        return jsonRpcResult(id, result)
    }

    private fun callGetDiagnostics(arguments: JsonObject): String {
        val requestedPath = arguments.get("filePath")?.asString
        return readOnEdt {
            val fem = FileEditorManager.getInstance(project)
            val editor = if (requestedPath != null) {
                val vf = LocalFileSystem.getInstance().findFileByPath(requestedPath)
                if (vf != null) fem.openFiles
                    .filter { it.path == requestedPath }
                    .firstNotNullOfOrNull { f -> fem.getEditors(f).filterIsInstance<TextEditor>().firstOrNull() }
                else null
            } else {
                fem.selectedEditor as? TextEditor
            }

            if (editor == null) return@readOnEdt "No active editor${requestedPath?.let { " for $it" } ?: ""}"

            val document = editor.editor.document
            val vFile = editor.file
            val basePath = project.basePath ?: ""
            val relPath = vFile.path.removePrefix(basePath).trimStart('/')
            val markup = DocumentMarkupModel.forDocument(document, project, false) ?: return@readOnEdt "No diagnostics available"

            val problems = mutableListOf<String>()
            for (h in markup.allHighlighters) {
                val info = HighlightInfo.fromRangeHighlighter(h) ?: continue
                if (info.severity < HighlightSeverity.WARNING) continue
                val line = document.getLineNumber(h.startOffset) + 1
                val severity = if (info.severity >= HighlightSeverity.ERROR) "ERROR" else "WARNING"
                val desc = (info.description ?: info.toolTip ?: "").replace(Regex("<[^>]+>"), "").trim()
                if (desc.isNotEmpty()) problems.add("$relPath:$line: $severity: $desc")
            }

            if (problems.isEmpty()) "No errors or warnings in $relPath"
            else "IDE diagnostics for $relPath (${problems.size} problems):\n${problems.joinToString("\n")}"
        }
    }

    private fun callGetSelection(): String {
        return readOnEdt {
            val fem = FileEditorManager.getInstance(project)
            val textEditor = fem.selectedEditor as? TextEditor ?: return@readOnEdt "No active text editor"
            val editor = textEditor.editor
            val sm = editor.selectionModel

            if (!sm.hasSelection()) return@readOnEdt "No text selected"

            val vFile = textEditor.file
            val basePath = project.basePath ?: ""
            val relPath = vFile.path.removePrefix(basePath).trimStart('/')
            val startLine = editor.document.getLineNumber(sm.selectionStart) + 1
            val endLine = editor.document.getLineNumber(sm.selectionEnd) + 1
            val text = sm.selectedText ?: ""

            val lineRef = if (startLine == endLine) "L$startLine" else "L$startLine-L$endLine"
            "$relPath#$lineRef\n```\n$text\n```"
        }
    }

    private fun callGetOpenFiles(): String {
        return readOnEdt {
            val fem = FileEditorManager.getInstance(project)
            val basePath = project.basePath ?: ""
            val files = fem.openFiles.map { it.path.removePrefix(basePath).trimStart('/') }
            if (files.isEmpty()) "No files open"
            else "Open files (${files.size}):\n${files.joinToString("\n") { "- $it" }}"
        }
    }

    private fun callShowDiff(arguments: JsonObject): String {
        val filePath = arguments.get("filePath")?.asString ?: return "Missing filePath"
        val before = arguments.get("before")?.asString ?: return "Missing before content"
        val after = arguments.get("after")?.asString ?: return "Missing after content"
        val title = arguments.get("title")?.asString ?: filePath.substringAfterLast("/")

        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
            val fileType = vf?.fileType ?: FileTypeManager.getInstance().getFileTypeByFileName(filePath.substringAfterLast("/"))
            val factory = DiffContentFactory.getInstance()
            val beforeContent = factory.create(project, before, fileType)
            val afterContent = factory.create(project, after, fileType)
            val request = SimpleDiffRequest(title, beforeContent, afterContent, "Before", "After")
            DiffManager.getInstance().showDiff(project, request)
        }
        return "Diff viewer opened for $filePath"
    }

    // ── resources/list ──────────────────────────────────────────────────

    private fun handleResourcesList(id: Int): String {
        val resources = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("uri", "ide://selection")
                addProperty("name", "Current Selection")
                addProperty("description", "The currently selected text in the editor")
                addProperty("mimeType", "text/plain")
            })
            add(JsonObject().apply {
                addProperty("uri", "ide://diagnostics")
                addProperty("name", "Current File Diagnostics")
                addProperty("description", "Compiler errors and warnings for the active file")
                addProperty("mimeType", "text/plain")
            })
        }
        val result = JsonObject().apply { add("resources", resources) }
        return jsonRpcResult(id, result)
    }

    // ── resources/read ──────────────────────────────────────────────────

    private fun handleResourcesRead(id: Int, root: JsonObject): String {
        val params = root.getAsJsonObject("params") ?: JsonObject()
        val uri = params.get("uri")?.asString ?: return jsonRpcError(id, -32602, "Missing uri")

        val text = when (uri) {
            "ide://selection" -> callGetSelection()
            "ide://diagnostics" -> callGetDiagnostics(JsonObject())
            else -> return jsonRpcError(id, -32602, "Unknown resource: $uri")
        }

        val contents = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("uri", uri)
                addProperty("mimeType", "text/plain")
                addProperty("text", text)
            })
        }
        val result = JsonObject().apply { add("contents", contents) }
        return jsonRpcResult(id, result)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Run a block on the EDT with read access, returning its result. */
    private fun readOnEdt(block: () -> String): String {
        return ApplicationManager.getApplication().runReadAction(Computable { block() })
    }

    private fun mcpTool(name: String, description: String, inputSchema: String): JsonObject =
        JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("inputSchema", JsonParser.parseString(inputSchema))
        }

    private fun jsonRpcResult(id: Int, result: JsonObject): String {
        val envelope = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            add("result", result)
        }
        return envelope.toString()
    }

    private fun jsonRpcError(id: Int, code: Int, message: String): String {
        val envelope = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }
        return envelope.toString()
    }

    override fun dispose() {
        try {
            HttpRequestHandler.EP_NAME.extensionList
                .filterIsInstance<IdeWebSocketHandler>()
                .firstOrNull()?.onMessage = null
        } catch (_: Exception) {}
        log.warn("[MCP] server stopped")
    }
}
