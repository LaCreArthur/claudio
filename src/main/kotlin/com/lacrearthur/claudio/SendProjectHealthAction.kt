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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.wm.ToolWindowManager

class SendProjectHealthAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: ""

        val allEditors = FileEditorManager.getInstance(project).allEditors
        if (allEditors.isEmpty()) {
            Notifications.Bus.notify(
                Notification("Claudio", "No open files", "Open some files to get a project health snapshot.", NotificationType.INFORMATION),
                project
            )
            return
        }

        // file relative path -> (errors, warnings)
        val results = mutableMapOf<String, Pair<Int, Int>>()

        for (editor in allEditors) {
            val textEditor = editor as? TextEditor ?: continue
            val vFile = textEditor.file ?: continue
            val doc = FileDocumentManager.getInstance().getDocument(vFile) ?: continue
            val markupModel = DocumentMarkupModel.forDocument(doc, project, false) ?: continue

            var errors = 0
            var warnings = 0
            for (h in markupModel.allHighlighters) {
                val info = HighlightInfo.fromRangeHighlighter(h) ?: continue
                when {
                    info.severity.myVal >= HighlightSeverity.ERROR.myVal -> errors++
                    info.severity.myVal >= HighlightSeverity.WARNING.myVal -> warnings++
                }
            }
            if (errors > 0 || warnings > 0) {
                val rel = if (basePath.isNotEmpty() && vFile.path.startsWith(basePath)) {
                    vFile.path.removePrefix(basePath).trimStart('/')
                } else {
                    vFile.name
                }
                results[rel] = Pair(errors, warnings)
            }
        }

        if (results.isEmpty()) {
            Notifications.Bus.notify(
                Notification("Claudio", "No issues found", "No errors or warnings found in currently open files.", NotificationType.INFORMATION),
                project
            )
            return
        }

        // Sort by total issue count descending
        val sorted = results.entries.sortedByDescending { (_, counts) -> counts.first + counts.second }

        val totalErrors = results.values.sumOf { it.first }
        val totalWarnings = results.values.sumOf { it.second }
        val fileCount = results.size

        val lines = sorted.map { (file, counts) ->
            val (errors, warnings) = counts
            val errPart = if (errors == 1) "1 error" else "$errors errors"
            val warnPart = if (warnings == 1) "1 warning" else "$warnings warnings"
            "$file: $errPart, $warnPart"
        }

        val errSummary = if (totalErrors == 1) "1 error" else "$totalErrors errors"
        val warnSummary = if (totalWarnings == 1) "1 warning" else "$totalWarnings warnings"
        val fileSummary = if (fileCount == 1) "1 file" else "$fileCount files"

        val text = buildString {
            append("Project health (open files, $fileCount with issues):\n")
            append(lines.joinToString("\n"))
            append("\n\nSummary: $errSummary, $warnSummary across $fileSummary")
            append("\n(analysis based on currently open files - open more files to expand coverage)")
        }

        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
        tw.show {
            val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(text)
        }
    }
}
