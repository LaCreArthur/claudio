package com.lacrearthur.claudio

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.wm.ToolWindowManager

class SendRecentFilesAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        // getAllEditors() returns editors in most-recently-focused-first order in most IDEs.
        // Filter to text editors only and deduplicate by path.
        val seen = linkedSetOf<String>()
        FileEditorManager.getInstance(project).allEditors
            .mapNotNull { (it as? TextEditor)?.file }
            .filter { it.path.startsWith(basePath) }
            .forEach { seen.add(it.path.removePrefix(basePath).trimStart('/')) }

        val files = seen.take(15).toList()

        if (files.isEmpty()) {
            Notifications.Bus.notify(
                Notification("Claudio", "No open files", "No project files are currently open.", NotificationType.INFORMATION),
                project
            )
            return
        }

        val lines = files.mapIndexed { i, rel -> "${i + 1}. $rel" }
        val text = "Currently open files (${files.size}):\n${lines.joinToString("\n")}"

        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
        tw.show {
            val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(text)
        }
    }
}
