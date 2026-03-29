package com.lacrearthur.claudio

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.SwingUtilities

/**
 * Right-click any selection (or current line) → "Send Selection History to Claude"
 * Runs `git log -L startLine,endLine:relativePath` and injects the output into the input bar.
 * Answers "when did this break and who touched it" without leaving the IDE.
 */
class SendVcsSelectionHistoryAction : AnAction("Send Selection History to Claude") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val vFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val basePath = project.basePath ?: return

        val selection = editor.selectionModel
        val startLine: Int
        val endLine: Int
        if (selection.hasSelection()) {
            startLine = editor.document.getLineNumber(selection.selectionStart) + 1
            endLine = editor.document.getLineNumber(selection.selectionEnd) + 1
        } else {
            val line = editor.caretModel.logicalPosition.line + 1
            startLine = line
            endLine = line
        }

        val relativePath = vFile.path.removePrefix(basePath).removePrefix("/")

        ApplicationManager.getApplication().executeOnPooledThread {
            val output = runGitLogL(basePath, startLine, endLine, relativePath)
            SwingUtilities.invokeLater {
                if (output == null) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Claudio")
                        .createNotification(
                            "No git history found for this selection (not a git repo or no commits touch this range).",
                            NotificationType.INFORMATION
                        )
                        .notify(project)
                    return@invokeLater
                }
                val tw = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return@invokeLater
                tw.show {
                    val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
                    panel.setInputText(output)
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        private fun runGitLogL(basePath: String, startLine: Int, endLine: Int, relativePath: String): String? {
            return try {
                val process = ProcessBuilder(
                    "git", "log",
                    "-L", "$startLine,$endLine:$relativePath",
                    "--pretty=format:%h %an %ad %s",
                    "--date=short",
                    "-10"
                )
                    .directory(java.io.File(basePath))
                    .redirectErrorStream(true)
                    .start()
                val raw = process.inputStream.bufferedReader().readText()
                process.waitFor()

                if (raw.isBlank()) return null

                val lines = raw.lines()
                val truncated = lines.size > 100
                val body = if (truncated) {
                    lines.take(100).joinToString("\n") + "\n... (truncated, ${lines.size - 100} more lines)"
                } else raw.trimEnd()

                val lineRange = if (startLine == endLine) "line $startLine" else "lines $startLine-$endLine"
                "Git history for $relativePath $lineRange (10 most recent commits):\n\n$body"
            } catch (_: Exception) {
                null
            }
        }
    }
}
