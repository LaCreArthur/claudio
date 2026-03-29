package com.lacrearthur.claudio

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.SwingUtilities

/**
 * Injects the full working-tree diff (staged + unstaged) into the Claudio input bar.
 * Uses `git diff` subprocess - no git4idea dependency needed.
 */
class SendDiffToClaudeAction : AnAction("Send Uncommitted Diff to Claude") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        perform(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        fun perform(project: Project) {
            val basePath = project.basePath ?: return
            ApplicationManager.getApplication().executeOnPooledThread {
                val diff = buildDiff(basePath)
                SwingUtilities.invokeLater {
                    if (diff == null) {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Claudio")
                            .createNotification("No uncommitted changes", NotificationType.INFORMATION)
                            .notify(project)
                        return@invokeLater
                    }
                    val tw = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return@invokeLater
                    tw.show {
                        val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
                        panel.setInputText(diff)
                    }
                }
            }
        }

        private fun buildDiff(basePath: String): String? {
            val staged = runGit(basePath, "git", "diff", "--cached", "--no-color")
            val unstaged = runGit(basePath, "git", "diff", "--no-color")
            val combined = "$staged\n$unstaged".trim()
            if (combined.isEmpty()) return null

            val lines = combined.lines()
            val body = if (lines.size > 100) {
                lines.take(100).joinToString("\n") + "\n... (truncated, ${lines.size - 100} more lines)"
            } else combined

            return "Here are my uncommitted changes:\n\n$body"
        }

        private fun runGit(basePath: String, vararg cmd: String): String = try {
            ProcessBuilder(*cmd)
                .directory(java.io.File(basePath))
                .start()
                .inputStream.bufferedReader().readText()
        } catch (_: Exception) { "" }
    }
}
