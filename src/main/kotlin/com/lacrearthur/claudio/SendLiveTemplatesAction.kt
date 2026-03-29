package com.lacrearthur.claudio

import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Collects all active live template abbreviations from TemplateSettings and injects them into
 * Claudio's input bar so Claude knows what code shortcuts are already defined.
 */
class SendLiveTemplatesAction : AnAction("Send Live Templates to Claude") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val prompt = buildPrompt()
        if (prompt == null) {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("Claudio")
                .createNotification("No active live templates found.", com.intellij.notification.NotificationType.INFORMATION)
                .notify(project)
            return
        }
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
        tw.show {
            val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(prompt)
        }
    }

    private fun buildPrompt(): String? {
        val settings = TemplateSettings.getInstance()
        val templates = settings.templatesAsList
            .filter { !it.isDeactivated }
            .sortedWith(compareBy({ it.groupName ?: "" }, { it.key ?: "" }))
            .take(60)

        if (templates.isEmpty()) return null

        val sb = StringBuilder("Live templates (${templates.size} active):\n\n")
        for (tmpl in templates) {
            val key = tmpl.key ?: continue
            val group = tmpl.groupName?.takeIf { it.isNotBlank() } ?: "Default"
            val desc = tmpl.description?.takeIf { it.isNotBlank() } ?: "(no description)"
            sb.append("- $key ($group): $desc\n")
        }
        sb.append("\nUse these abbreviations when suggesting code snippets - I already have them set up.")
        return sb.toString()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
