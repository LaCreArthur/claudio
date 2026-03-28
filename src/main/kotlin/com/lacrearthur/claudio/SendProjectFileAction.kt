package com.lacrearthur.claudio

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiManager

/**
 * Project view right-click → "Send to Claude":
 * - Project files: inject @filepath into the input bar
 * - Library/JAR class files: inject decompiled source via PSI (Fernflower)
 */
class SendProjectFileAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val basePath = project.basePath ?: ""

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return

        if (file.path.startsWith(basePath)) {
            val rel = file.path.removePrefix(basePath).trimStart('/')
            toolWindow.show {
                val panel = toolWindow.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
                panel.appendToInput("@$rel")
            }
        } else {
            // Library or JAR class: inject decompiled/attached source via PSI
            val psiFile = PsiManager.getInstance(project).findFile(file)
            val source = psiFile?.text
            if (source == null) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Claudio")
                    .createNotification("Cannot read this file", NotificationType.WARNING)
                    .notify(project)
                return
            }
            val lines = source.lines()
            val body = if (lines.size > 200) {
                lines.take(200).joinToString("\n") + "\n// ... (truncated, ${lines.size - 200} more lines)"
            } else source
            val prompt = "Here is the source of ${file.nameWithoutExtension}:\n\n$body"
            toolWindow.show {
                val panel = toolWindow.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
                panel.setInputText(prompt)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && !file.isDirectory
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
