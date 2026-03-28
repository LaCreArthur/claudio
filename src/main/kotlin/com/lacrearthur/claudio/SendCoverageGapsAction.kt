package com.lacrearthur.claudio

import com.intellij.coverage.CoverageDataManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.rt.coverage.data.LineData

/**
 * After running with coverage, injects classes with < 80% line coverage into the input bar.
 * CoverageDataManager is IDE-only - no terminal equivalent.
 */
class SendCoverageGapsAction : AnAction("Send Uncovered Methods to Claude") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val bundle = CoverageDataManager.getInstance(project).currentSuitesBundle ?: run {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claudio")
                .createNotification("No coverage data available - run tests with coverage first", NotificationType.INFORMATION)
                .notify(project)
            return
        }

        val projectData = bundle.getCoverageData() ?: run {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claudio")
                .createNotification("Coverage data not yet computed", NotificationType.INFORMATION)
                .notify(project)
            return
        }

        data class ClassCoverage(val fqn: String, val covered: Int, val total: Int) {
            val pct: Int get() = if (total == 0) 100 else covered * 100 / total
        }

        val gaps = mutableListOf<ClassCoverage>()
        for ((fqn, classData) in projectData.classes) {
            val lines = classData.lines ?: continue
            var total = 0; var covered = 0
            for (item in lines) {
                val ld = item as? LineData ?: continue
                total++
                if (ld.hits > 0) covered++
            }
            if (total > 0 && covered * 100 / total < 80) {
                gaps.add(ClassCoverage(fqn.replace('/', '.'), covered, total))
            }
        }

        if (gaps.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claudio")
                .createNotification("All classes are above 80% coverage", NotificationType.INFORMATION)
                .notify(project)
            return
        }

        val sorted = gaps.sortedBy { it.pct }.take(30)
        val prompt = buildString {
            append("Here are classes with coverage below 80% (${sorted.size} total):\n\n")
            for (c in sorted) {
                append("- ${c.fqn}: ${c.pct}% (${c.covered}/${c.total} lines)\n")
            }
            append("\nPlease write tests to improve coverage for these classes.")
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
