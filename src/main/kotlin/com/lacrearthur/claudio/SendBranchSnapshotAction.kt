package com.lacrearthur.claudio

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.SwingUtilities

/**
 * Tools > Send Branch Snapshot to Claude
 * Runs `git branch` (last 10 by commit date) + `git stash list` and injects the VCS state
 * into the Claudio input bar. Essential context before asking Claude to help with merges,
 * rebases, or "what was I working on" questions.
 */
class SendBranchSnapshotAction : AnAction("Send Branch Snapshot to Claude") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val snapshot = buildSnapshot(basePath)
            SwingUtilities.invokeLater {
                if (snapshot == null) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Claudio")
                        .createNotification(
                            "Not a git repository or git is unavailable.",
                            NotificationType.INFORMATION
                        )
                        .notify(project)
                    return@invokeLater
                }
                val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return@invokeLater
                tw.show {
                    val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
                    panel.setInputText(snapshot)
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        private fun buildSnapshot(basePath: String): String? {
            return try {
                val workDir = java.io.File(basePath)

                // Run both processes in parallel
                val branchProcess = ProcessBuilder(
                    "git", "branch",
                    "--sort=-committerdate",
                    "--format=%(HEAD) %(refname:short) %(objectname:short) %(committerdate:relative)",
                    "--all"
                ).directory(workDir).redirectErrorStream(true).start()

                val stashProcess = ProcessBuilder(
                    "git", "stash", "list",
                    "--format=%gd: %s (%cr)"
                ).directory(workDir).redirectErrorStream(true).start()

                val branchRaw = branchProcess.inputStream.bufferedReader().readText()
                branchProcess.waitFor()

                val stashRaw = stashProcess.inputStream.bufferedReader().readText()
                stashProcess.waitFor()

                // Bail if not a git repo (git outputs error text, no branch lines starting with * or space)
                if (branchRaw.isBlank() && stashRaw.isBlank()) return null

                val branchLines = branchRaw.lines().filter { it.isNotBlank() }
                if (branchLines.isEmpty()) return null

                val sb = StringBuilder()
                sb.appendLine("VCS state for project:")
                sb.appendLine()

                // Current branch: find the line starting with "* "
                val currentLine = branchLines.firstOrNull { it.startsWith("* ") }
                if (currentLine != null) {
                    val parts = currentLine.removePrefix("* ").trim().split("\\s+".toRegex(), limit = 3)
                    val branchName = parts.getOrElse(0) { "unknown" }
                    val sha = parts.getOrElse(1) { "" }
                    sb.appendLine("Current branch: $branchName ($sha)")
                    sb.appendLine()
                }

                // Recent branches (up to 10)
                val recentBranches = branchLines.take(10)
                sb.appendLine("Recent branches (last ${recentBranches.size}):")
                for (line in recentBranches) {
                    sb.appendLine("  $line")
                }

                // Stash list
                sb.appendLine()
                val stashLines = stashRaw.lines().filter { it.isNotBlank() }.take(10)
                if (stashLines.isEmpty()) {
                    sb.appendLine("Stashes: none")
                } else {
                    sb.appendLine("Stashes (${stashLines.size}):")
                    for (line in stashLines) {
                        sb.appendLine("  $line")
                    }
                }

                sb.toString().trimEnd()
            } catch (_: Exception) {
                null
            }
        }
    }
}
