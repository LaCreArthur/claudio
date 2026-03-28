package com.lacrearthur.claudio

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Right-click on any code → "Ask Claude to Explain" → injects selection + file:line into Claudio's input bar.
 * Falls back to the current line when nothing is selected.
 */
class ExplainWithClaudeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        val selection = editor.selectionModel
        val code = if (selection.hasSelection()) {
            selection.selectedText ?: return
        } else {
            val line = editor.caretModel.logicalPosition.line
            val start = editor.document.getLineStartOffset(line)
            val end = editor.document.getLineEndOffset(line)
            editor.document.getText(TextRange(start, end)).trim()
        }
        if (code.isBlank()) return

        val line = editor.document.getLineNumber(editor.caretModel.offset) + 1
        val relativePath = file?.path?.removePrefix(project.basePath ?: "")?.removePrefix("/")
            ?: file?.name ?: "unknown"
        val prompt = "Explain this ($relativePath:$line):\n```\n$code\n```"

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
        toolWindow.show {
            val content = toolWindow.contentManager.getContent(0) ?: return@show
            val panel = content.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(prompt)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
