package com.lacrearthur.claudio

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captures IDE notifications for the lifetime of the plugin session and injects the last 15
 * into the Claudio input bar so the user can ask Claude "what does this mean".
 *
 * NotificationsManagerImpl (impl package) has no stable binary contract, so we capture
 * notifications via a message bus listener registered at first use instead of querying history.
 */
class SendNotificationLogAction : AnAction("Send Notification Log to Claude") {

    companion object {
        /** In-session notification ring buffer, max 20, newest last. Thread-safe. */
        private val captured: CopyOnWriteArrayList<CapturedNotification> = CopyOnWriteArrayList()
        private var listenerInstalled = false

        private data class CapturedNotification(
            val type: NotificationType,
            val groupId: String,
            val title: String,
            val content: String,
        )

        /**
         * Register a project-scoped Notifications bus listener once per plugin session.
         * Safe to call multiple times - noop after first install.
         */
        fun ensureListenerInstalled(project: Project) {
            if (listenerInstalled) return
            listenerInstalled = true
            project.messageBus.connect().subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    if (captured.size >= 20) captured.removeAt(0)
                    captured.add(
                        CapturedNotification(
                            type = notification.type,
                            groupId = notification.groupId,
                            title = notification.title,
                            content = stripHtml(notification.content),
                        )
                    )
                }
            })
        }

        private fun stripHtml(html: String): String =
            html.replace(Regex("<[^>]+>"), "").trim()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ensureListenerInstalled(project)

        val recent = captured.takeLast(15)
        if (recent.isEmpty()) {
            val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
            tw.show {
                val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel
                    ?: return@show
                panel.setInputText(
                    "No IDE notifications have been captured since the plugin started. " +
                        "Trigger an IDE operation to generate some, then try again."
                )
            }
            return
        }

        val prompt = buildPrompt(recent)
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
        tw.show {
            val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel
                ?: return@show
            panel.setInputText(prompt)
        }
    }

    private fun buildPrompt(notifications: List<CapturedNotification>): String {
        val sb = StringBuilder("Recent IDE notifications (${notifications.size}):\n\n")
        notifications.forEachIndexed { i, n ->
            val typeLabel = when (n.type) {
                NotificationType.ERROR -> "ERROR"
                NotificationType.WARNING -> "WARNING"
                else -> "INFO"
            }
            val group = if (n.groupId.isNotBlank()) n.groupId else "IDE"
            val title = n.title.ifBlank { "(no title)" }
            val content = n.content.ifBlank { "(no content)" }
            if (title == content || n.content.isBlank()) {
                sb.append("${i + 1}. [$typeLabel] $group: $title\n")
            } else {
                sb.append("${i + 1}. [$typeLabel] $group: $title - $content\n")
            }
        }
        sb.append("\nCan you explain what these notifications mean and whether any of them need attention?")
        return sb.toString()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
