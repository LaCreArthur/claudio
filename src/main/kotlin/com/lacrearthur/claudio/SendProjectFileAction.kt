package com.lacrearthur.claudio

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Project view right-click → "Send to Claude": inject @filepath into the Claudio input bar.
 */
class SendProjectFileAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val rel = file.path.removePrefix(project.basePath ?: "").trimStart('/')
        val ref = "@$rel"

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
        toolWindow.show {
            val content = toolWindow.contentManager.getContent(0) ?: return@show
            val panel = content.component as? ClaudioTabbedPanel ?: return@show
            panel.appendToInput(ref)
        }
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && !file.isDirectory
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
