package com.lacrearthur.claudio

import com.intellij.ide.scratch.ScratchUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowManager

private const val MAX_LINES = 200

/**
 * Tools > Send Scratch File to Claude
 *
 * Checks whether the active editor file is a scratch file. If yes, reads its content
 * (capped at 200 lines) and injects it into the Claudio input bar. If not, shows a
 * balloon notification telling the user to open a scratch file first.
 */
class SendScratchFileAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val vFile = FileEditorManager.getInstance(project).selectedEditor?.file
        if (vFile == null || !ScratchUtil.isScratch(vFile)) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claudio")
                .createNotification(
                    "No scratch file is currently open. Open a scratch file (File > New Scratch File) and try again.",
                    NotificationType.INFORMATION
                )
                .notify(project)
            return
        }

        val rawContent = VfsUtil.loadText(vFile)
        val lines = rawContent.lines()
        val truncated = lines.size > MAX_LINES
        val content = if (truncated) {
            lines.take(MAX_LINES).joinToString("\n") + "\n(truncated at $MAX_LINES lines)"
        } else {
            rawContent
        }

        val formatted = "Scratch file (${vFile.name}):\n$content\n"

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
        toolWindow.show {
            val tw = toolWindow.contentManager.getContent(0) ?: return@show
            val panel = tw.component as? ClaudioTabbedPanel ?: return@show
            panel.appendToInput(formatted)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
