package com.lacrearthur.claudio

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.gradle.util.GradleConstants

class SendBuildTasksAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val tasks = collectGradleTasks(project)

        if (tasks.isEmpty()) {
            Notifications.Bus.notify(
                Notification(
                    "Claudio",
                    "No Gradle tasks found",
                    "This project does not appear to be linked as a Gradle project. " +
                        "Import the project via File > Open/Link Gradle Project first.",
                    NotificationType.WARNING
                ),
                project
            )
            return
        }

        val text = formatTasks(tasks)

        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
        tw.show {
            val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(text)
        }
    }

    private data class TaskInfo(val name: String, val description: String, val group: String)

    private fun collectGradleTasks(project: com.intellij.openapi.project.Project): List<TaskInfo> {
        return try {
            val projectsData = ProjectDataManager.getInstance()
                .getExternalProjectsData(project, GradleConstants.SYSTEM_ID)

            val tasks = mutableListOf<TaskInfo>()
            for (projectData in projectsData) {
                val structure = projectData.externalProjectStructure ?: continue
                val taskNodes = ExternalSystemApiUtil.findAll(structure, ProjectKeys.TASK)
                for (node in taskNodes) {
                    val task = node.data
                    tasks.add(
                        TaskInfo(
                            name = task.name,
                            description = task.description?.trim() ?: "",
                            group = task.group?.trim()?.lowercase() ?: "other"
                        )
                    )
                }
            }
            // Deduplicate by name (multi-module projects repeat common tasks)
            tasks.distinctBy { it.name }
        } catch (_: Throwable) {
            // Catches NoClassDefFoundError when Gradle plugin is disabled
            emptyList()
        }
    }

    private fun formatTasks(tasks: List<TaskInfo>): String {
        val cap = 60
        val capped = tasks.take(cap)

        // Group tasks, normalize empty/null group to "other"
        val grouped = capped.groupBy { t ->
            t.group.ifBlank { "other" }
        }

        // Preferred order for well-known groups
        val preferredOrder = listOf("build", "verification", "application", "distribution", "documentation", "publishing", "help")
        val sortedGroups = grouped.keys.sortedWith(
            compareBy { key -> preferredOrder.indexOf(key).let { if (it == -1) Int.MAX_VALUE else it } }
        )

        val sb = StringBuilder()
        sb.append("Gradle tasks (${capped.size}${if (tasks.size > cap) ", capped at $cap" else ""}):\n")

        for (group in sortedGroups) {
            sb.append("${group}:\n")
            for (task in grouped[group] ?: emptyList()) {
                if (task.description.isNotEmpty()) {
                    sb.append("  - ${task.name}: ${task.description}\n")
                } else {
                    sb.append("  - ${task.name}\n")
                }
            }
        }

        return sb.toString().trimEnd()
    }
}
