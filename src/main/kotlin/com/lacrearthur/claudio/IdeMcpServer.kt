package com.lacrearthur.claudio

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
import javax.swing.SwingUtilities

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
            manager.waitForStart()
            builtInPort = manager.port
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
        val method = JsonUtils.extractString(message, "method")
        val id = JsonUtils.extractInt(message, "id")

        // Notifications (no id) - don't respond
        if (id == null) {
            log.warn("[MCP] notification: $method")
            return null
        }

        log.warn("[MCP] request: $method (id=$id)")
        return try {
            when (method) {
                "initialize" -> handleInitialize(id, message)
                "tools/list" -> handleToolsList(id)
                "tools/call" -> handleToolsCall(id, message)
                "resources/list" -> handleResourcesList(id)
                "resources/read" -> handleResourcesRead(id, message)
                else -> jsonRpcError(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            log.warn("[MCP] error handling $method: ${e.message}")
            jsonRpcError(id, -32603, "Internal error: ${e.message}")
        }
    }

    // ── initialize ──────────────────────────────────────────────────────

    private fun handleInitialize(id: Int, message: String): String {
        val params = JsonUtils.extractObject(message, "params")
        val clientVersion = JsonUtils.extractString(params, "protocolVersion") ?: "2024-11-05"
        val result = JsonUtils.buildJsonObject(
            "protocolVersion" to clientVersion,
            "serverInfo" to JsonUtils.RawJson(JsonUtils.buildJsonObject(
                "name" to "claudio-ide",
                "version" to "0.1.0",
            )),
            "capabilities" to JsonUtils.RawJson(JsonUtils.buildJsonObject(
                "tools" to JsonUtils.RawJson("""{"listChanged":false}"""),
                "resources" to JsonUtils.RawJson("""{"subscribe":false,"listChanged":false}"""),
            )),
        )
        return jsonRpcResult(id, result)
    }

    // ── tools/list ──────────────────────────────────────────────────────

    private fun handleToolsList(id: Int): String {
        val tools = JsonUtils.buildJsonArray(listOf(
            mcpTool("getDiagnostics", "Get compiler errors and warnings from the IDE for the current file or a specified file path",
                """{"type":"object","properties":{"filePath":{"type":"string","description":"Absolute file path. If omitted, uses the currently active editor."}},"required":[]}"""),
            mcpTool("getSelection", "Get the current editor selection text, file path, and line range",
                """{"type":"object","properties":{},"required":[]}"""),
            mcpTool("getOpenFiles", "Get the list of currently open editor tabs with file paths",
                """{"type":"object","properties":{},"required":[]}"""),
            mcpTool("showDiff", "Open the IDE's native diff viewer to compare original and modified file content",
                """{"type":"object","properties":{"filePath":{"type":"string","description":"Absolute file path"},"before":{"type":"string","description":"Original file content"},"after":{"type":"string","description":"Modified file content"},"title":{"type":"string","description":"Diff viewer title (optional)"}},"required":["filePath","before","after"]}"""),
        ))
        val result = JsonUtils.buildJsonObject("tools" to JsonUtils.RawJson(tools))
        return jsonRpcResult(id, result)
    }

    // ── tools/call ──────────────────────────────────────────────────────

    private fun handleToolsCall(id: Int, message: String): String {
        val params = JsonUtils.extractObject(message, "params")
        val toolName = JsonUtils.extractString(params, "name") ?: return jsonRpcError(id, -32602, "Missing tool name")
        val arguments = JsonUtils.extractObject(params, "arguments")

        val content = when (toolName) {
            "getDiagnostics" -> callGetDiagnostics(arguments)
            "getSelection" -> callGetSelection()
            "getOpenFiles" -> callGetOpenFiles()
            "showDiff" -> callShowDiff(arguments)
            else -> return jsonRpcError(id, -32602, "Unknown tool: $toolName")
        }

        val result = JsonUtils.buildJsonObject(
            "content" to JsonUtils.RawJson(JsonUtils.buildJsonArray(listOf(
                JsonUtils.buildJsonObject("type" to "text", "text" to content)
            )))
        )
        return jsonRpcResult(id, result)
    }

    private fun callGetDiagnostics(arguments: String): String {
        val requestedPath = JsonUtils.extractString(arguments, "filePath")
        return readOnEdt {
            val fem = FileEditorManager.getInstance(project)
            val editor = if (requestedPath != null) {
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(requestedPath)
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
                if (info.severity.myVal < HighlightSeverity.WARNING.myVal) continue
                val line = document.getLineNumber(h.startOffset) + 1
                val severity = if (info.severity.myVal >= HighlightSeverity.ERROR.myVal) "ERROR" else "WARNING"
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

    private fun callShowDiff(arguments: String): String {
        val filePath = JsonUtils.extractString(arguments, "filePath") ?: return "Missing filePath"
        val before = JsonUtils.extractString(arguments, "before") ?: return "Missing before content"
        val after = JsonUtils.extractString(arguments, "after") ?: return "Missing after content"
        val title = JsonUtils.extractString(arguments, "title") ?: filePath.substringAfterLast("/")

        SwingUtilities.invokeLater {
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
        val resources = JsonUtils.buildJsonArray(listOf(
            JsonUtils.buildJsonObject(
                "uri" to "ide://selection",
                "name" to "Current Selection",
                "description" to "The currently selected text in the editor",
                "mimeType" to "text/plain",
            ),
            JsonUtils.buildJsonObject(
                "uri" to "ide://diagnostics",
                "name" to "Current File Diagnostics",
                "description" to "Compiler errors and warnings for the active file",
                "mimeType" to "text/plain",
            ),
        ))
        val result = JsonUtils.buildJsonObject("resources" to JsonUtils.RawJson(resources))
        return jsonRpcResult(id, result)
    }

    // ── resources/read ──────────────────────────────────────────────────

    private fun handleResourcesRead(id: Int, message: String): String {
        val params = JsonUtils.extractObject(message, "params")
        val uri = JsonUtils.extractString(params, "uri") ?: return jsonRpcError(id, -32602, "Missing uri")

        val text = when (uri) {
            "ide://selection" -> callGetSelection()
            "ide://diagnostics" -> callGetDiagnostics("{}")
            else -> return jsonRpcError(id, -32602, "Unknown resource: $uri")
        }

        val contents = JsonUtils.buildJsonArray(listOf(
            JsonUtils.buildJsonObject("uri" to uri, "mimeType" to "text/plain", "text" to text)
        ))
        val result = JsonUtils.buildJsonObject("contents" to JsonUtils.RawJson(contents))
        return jsonRpcResult(id, result)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Run a block on the EDT with read access, returning its result. */
    private fun readOnEdt(block: () -> String): String {
        return ApplicationManager.getApplication().runReadAction(Computable { block() })
    }

    private fun mcpTool(name: String, description: String, inputSchema: String): String =
        JsonUtils.buildJsonObject(
            "name" to name,
            "description" to description,
            "inputSchema" to JsonUtils.RawJson(inputSchema),
        )

    private fun jsonRpcResult(id: Int, result: String): String =
        """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    private fun jsonRpcError(id: Int, code: Int, message: String): String =
        """{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"${message.replace("\"", "\\'")}"}}"""

    override fun dispose() {
        try {
            HttpRequestHandler.EP_NAME.extensionList
                .filterIsInstance<IdeWebSocketHandler>()
                .firstOrNull()?.onMessage = null
        } catch (_: Exception) {}
        log.warn("[MCP] server stopped")
    }
}
