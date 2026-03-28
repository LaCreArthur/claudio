package com.lacrearthur.claudio

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.wm.ToolWindowManager

class SendModuleGraphAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val modules = ModuleManager.getInstance(project).modules.take(50)

        if (modules.isEmpty()) {
            Notifications.Bus.notify(
                Notification("Claudio", "No modules", "No modules found in this project.", NotificationType.INFORMATION),
                project
            )
            return
        }

        val lines = modules.map { module ->
            val deps = ModuleRootManager.getInstance(module).dependencies
            if (deps.isEmpty()) {
                "- ${module.name} (no module dependencies)"
            } else {
                "- ${module.name} depends on: ${deps.joinToString(", ") { it.name }}"
            }
        }

        val text = "Project module dependencies (${modules.size}):\n${lines.joinToString("\n")}"

        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
        tw.show {
            val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(text)
        }
    }
}
