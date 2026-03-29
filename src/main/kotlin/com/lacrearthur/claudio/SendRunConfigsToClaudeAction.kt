package com.lacrearthur.claudio

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.RunManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Injects all run configurations as a formatted list into the Claudio input bar.
 * RunManager is IDE-only - no terminal equivalent.
 */
class SendRunConfigsToClaudeAction : AnAction("Send Run Configurations to Claude") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val configs = RunManager.getInstance(project).allConfigurationsList.take(20)

        if (configs.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claudio")
                .createNotification("No run configurations found", NotificationType.INFORMATION)
                .notify(project)
            return
        }

        val lines = configs.map { config ->
            val type = config.type.displayName
            val extras = (config as? CommonProgramRunConfigurationParameters)?.let { p ->
                buildString {
                    val wd = p.workingDirectory?.takeIf { it.isNotBlank() }
                    val params = p.programParameters?.takeIf { it.isNotBlank() }
                    if (wd != null) append(" in $wd")
                    if (params != null) append(" -- $params")
                }
            } ?: ""
            "- ${config.name} [$type]$extras"
        }

        val prompt = buildString {
            append("Here are the project's run configurations (${configs.size} total):\n\n")
            lines.forEach { append("$it\n") }
            append("\nWhich configuration is failing, and what's happening?")
        }

        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
        tw.show {
            val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(prompt)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
