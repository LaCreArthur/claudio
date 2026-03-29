package com.lacrearthur.claudio

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Right-click on any code → "Ask Claude to Explain" → injects selection + file:line + git blame into Claudio's input bar.
 * Falls back to the current line when nothing is selected.
 */
class ExplainWithClaudeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        val selection = editor.selectionModel
        val startLine: Int
        val code = if (selection.hasSelection()) {
            startLine = editor.document.getLineNumber(selection.selectionStart) + 1
            selection.selectedText ?: return
        } else {
            val line = editor.caretModel.logicalPosition.line
            startLine = line + 1
            val start = editor.document.getLineStartOffset(line)
            val end = editor.document.getLineEndOffset(line)
            editor.document.getText(TextRange(start, end)).trim()
        }
        if (code.isBlank()) return

        val relativePath = file?.path?.removePrefix(project.basePath ?: "")?.removePrefix("/")
            ?: file?.name ?: "unknown"
        val blame = getBlameInfo(project, file, startLine)
        val blameStr = if (blame != null) "\n$blame" else ""
        val prompt = "Explain this ($relativePath:$startLine):$blameStr\n```\n$code\n```"

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
        toolWindow.show {
            val content = toolWindow.contentManager.getContent(0) ?: return@show
            val panel = content.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(prompt)
        }
    }

    private fun getBlameInfo(project: Project, file: VirtualFile?, startLine: Int): String? {
        if (file == null) return null
        return try {
            val vcsManager = ProjectLevelVcsManager.getInstance(project)
            val vcs = vcsManager.getVcsFor(file) ?: return null
            val annotationProvider = vcs.annotationProvider ?: return null
            val annotation = annotationProvider.annotate(file)
            try {
                val lineIdx = startLine - 1
                if (lineIdx < 0) return null
                val revision = annotation.getLineRevisionNumber(lineIdx) ?: return null
                val shortHash = revision.asString().take(7)
                val tooltip = annotation.getToolTip(lineIdx) ?: ""
                val summary = tooltip.lines().firstOrNull { it.isNotBlank() } ?: ""
                if (summary.isNotEmpty()) "Git blame: $shortHash $summary"
                else "Git blame: $shortHash"
            } finally {
                annotation.dispose()
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
