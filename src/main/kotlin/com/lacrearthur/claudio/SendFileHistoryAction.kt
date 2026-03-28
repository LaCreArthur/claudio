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
 * Right-click in any editor -> "Send File History to Claude"
 * Runs `git log --pretty=format:"%h %an %ad %s" --date=short -20 -- <relativePath>` and injects
 * the output into the input bar. Answers "how did this file evolve" for architecture questions.
 */
class SendFileHistoryAction : AnAction("Send File History to Claude") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val vFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val basePath = project.basePath ?: return

        val relativePath = vFile.path.removePrefix(basePath).removePrefix("/")

        ApplicationManager.getApplication().executeOnPooledThread {
            val output = runGitLogFile(basePath, relativePath)
            SwingUtilities.invokeLater {
                if (output == null) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Claudio")
                        .createNotification(
                            "No git history found for this file (not a git repo or no commits touch this file).",
                            NotificationType.INFORMATION
                        )
                        .notify(project)
                    return@invokeLater
                }
                val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return@invokeLater
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
        private fun runGitLogFile(basePath: String, relativePath: String): String? {
            return try {
                val process = ProcessBuilder(
                    "git", "log",
                    "--pretty=format:%h %an %ad %s",
                    "--date=short",
                    "-20",
                    "--",
                    relativePath
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

                "Git history for $relativePath (last 20 commits):\n\n$body"
            } catch (_: Exception) {
                null
            }
        }
    }
}
