package com.lacrearthur.claudio

import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.LineBookmark
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Injects all editor bookmarks as a numbered file:line list into the Claudio input bar.
 * BookmarksManager is IDE-only - no terminal equivalent.
 */
class SendBookmarksToClaudeAction : AnAction("Send Bookmarks to Claude") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = BookmarksManager.getInstance(project) ?: return

        val entries = mutableListOf<String>()
        for (bookmark in manager.bookmarks) {
            if (bookmark is LineBookmark) {
                val rel = bookmark.file.path.removePrefix(project.basePath ?: "").trimStart('/')
                val line = bookmark.line + 1
                val desc = manager.getGroups(bookmark).firstOrNull()
                    ?.getDescription(bookmark)?.takeIf { it.isNotBlank() }
                entries.add(if (desc != null) "$rel:$line ($desc)" else "$rel:$line")
            }
        }

        if (entries.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claudio")
                .createNotification("No bookmarks found", NotificationType.INFORMATION)
                .notify(project)
            return
        }

        val prompt = buildString {
            append("Here are my bookmarked locations (${entries.size} total):\n\n")
            entries.forEachIndexed { i, entry -> append("${i + 1}. $entry\n") }
            append("\nWhat should I focus on?")
        }

        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
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
