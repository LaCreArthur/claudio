package com.lacrearthur.claudio

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager

class RunInspectionsForClaudeAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        val textEditor = FileEditorManager.getInstance(project).selectedEditor as? TextEditor
        if (textEditor == null) {
            Notifications.Bus.notify(
                Notification("Claudio", "No editor open", "Open a file to run inspections.", NotificationType.INFORMATION),
                project
            )
            return
        }

        val vFile = textEditor.file
        val editor = textEditor.editor
        val document = editor.document

        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        val relPath = if (vFile != null && vFile.path.startsWith(basePath)) {
            vFile.path.removePrefix(basePath).trimStart('/')
        } else {
            vFile?.name ?: "unknown"
        }

        val markupModel = DocumentMarkupModel.forDocument(document, project, false)
        if (markupModel == null) {
            Notifications.Bus.notify(
                Notification("Claudio", "No inspection results", "No inspection results available for $relPath. The daemon may still be analyzing.", NotificationType.INFORMATION),
                project
            )
            return
        }

        val highlighters = markupModel.allHighlighters
        val problems = highlighters.mapNotNull { h ->
            val info = HighlightInfo.fromRangeHighlighter(h) ?: return@mapNotNull null
            if (info.severity.myVal < HighlightSeverity.WARNING.myVal) return@mapNotNull null
            val startOffset = info.startOffset
            if (startOffset < 0 || startOffset > document.textLength) return@mapNotNull null
            val line = document.getLineNumber(startOffset) + 1
            val severity = if (info.severity.myVal >= HighlightSeverity.ERROR.myVal) "error" else "warning"
            val message = (info.description ?: info.toolTip ?: "").trim()
                .replace(Regex("<[^>]+>"), "") // strip HTML tags from toolTip
                .ifEmpty { return@mapNotNull null }
            "$relPath:$line: $severity: $message"
        }.distinct().take(50)

        if (problems.isEmpty()) {
            Notifications.Bus.notify(
                Notification(
                    "Claudio",
                    "No problems found",
                    "No warnings or errors found for $relPath. The daemon may still be analyzing.",
                    NotificationType.INFORMATION
                ),
                project
            )
            return
        }

        val text = "IDE inspections for $relPath (${problems.size} problem${if (problems.size == 1) "" else "s"}):\n${problems.joinToString("\n")}"

        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
        tw.show {
            val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(text)
        }
    }
}
