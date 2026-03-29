package com.lacrearthur.claudio

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import org.w3c.dom.Element
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the last 5 test runs from .idea/testHistory/ and injects a structured summary
 * (name, timestamp, pass/fail counts, duration, first failure message) into the input bar.
 * Natural trigger for "why are these tests flaky" or "what broke".
 * TestHistoryConfiguration is IDE-only; no terminal equivalent for structured test result history.
 */
class SendTestHistoryAction : AnAction("Send Test History to Claude") {

    data class TestRun(
        val name: String,
        val timestamp: Long,
        val total: Int,
        val failures: Int,
        val errors: Int,
        val time: Double,
        val firstFailureMessage: String?
    ) {
        val passed: Int get() = total - failures - errors
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        val historyDir = File("$basePath/.idea/testHistory")
        if (!historyDir.exists() || !historyDir.isDirectory) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claudio")
                .createNotification(
                    "No test history found - run tests in the IDE first (.idea/testHistory/ is empty)",
                    NotificationType.INFORMATION
                )
                .notify(project)
            return
        }

        val xmlFiles = historyDir.listFiles { f -> f.extension == "xml" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(5)
            ?: emptyList()

        if (xmlFiles.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claudio")
                .createNotification(
                    "No test history found - run tests in the IDE first (.idea/testHistory/ is empty)",
                    NotificationType.INFORMATION
                )
                .notify(project)
            return
        }

        val runs = xmlFiles.mapNotNull { parseTestResultFile(it) }

        if (runs.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claudio")
                .createNotification(
                    "Could not parse any test result files in .idea/testHistory/",
                    NotificationType.INFORMATION
                )
                .notify(project)
            return
        }

        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm")
        val prompt = buildString {
            append("Test history (last ${runs.size} run${if (runs.size == 1) "" else "s"}):\n\n")
            runs.forEachIndexed { idx, run ->
                val dateStr = dateFmt.format(Date(run.timestamp))
                val passStr = "${run.passed}/${run.total} passed"
                val timeStr = "%.1fs".format(run.time)
                if (run.failures == 0 && run.errors == 0) {
                    append("${idx + 1}. ${run.name} [$dateStr] - $passStr ($timeStr)\n")
                } else {
                    val failCount = run.failures + run.errors
                    append("${idx + 1}. ${run.name} [$dateStr] - $passStr - $failCount failure${if (failCount == 1) "" else "s"}:\n")
                    if (run.firstFailureMessage != null) {
                        append("   - ${run.firstFailureMessage}\n")
                    }
                }
            }
            append("\nPlease analyze these test results. Are there patterns in the failures? Why might these tests be flaky?")
        }

        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
        tw.show {
            val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(prompt)
        }
    }

    private fun parseTestResultFile(file: File): TestRun? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(file)
            doc.documentElement.normalize()

            // Support both <testsuite> and root <testsuites> wrapping a <testsuite>
            val root = doc.documentElement
            val suite: Element = when (root.tagName) {
                "testsuite" -> root
                "testsuites" -> {
                    val children = root.getElementsByTagName("testsuite")
                    if (children.length == 0) return null
                    children.item(0) as? Element ?: return null
                }
                else -> return null
            }

            val name = suite.getAttribute("name").ifBlank { file.nameWithoutExtension }
            val total = suite.getAttribute("tests").toIntOrNull() ?: 0
            val failures = suite.getAttribute("failures").toIntOrNull() ?: 0
            val errors = suite.getAttribute("errors").toIntOrNull() ?: 0
            val time = suite.getAttribute("time").toDoubleOrNull() ?: 0.0

            // Extract first failure/error message from testcase children
            val firstFailureMsg: String? = run {
                val testcases = suite.getElementsByTagName("testcase")
                var msg: String? = null
                outer@ for (i in 0 until testcases.length) {
                    val tc = testcases.item(i) as? Element ?: continue
                    val suiteName = tc.getAttribute("classname").ifBlank { "" }
                    val testName = tc.getAttribute("name").ifBlank { "" }
                    val label = if (suiteName.isNotBlank()) "$suiteName.$testName" else testName
                    for (tag in listOf("failure", "error")) {
                        val nodes = tc.getElementsByTagName(tag)
                        if (nodes.length > 0) {
                            val node = nodes.item(0) as? Element ?: continue
                            val text = (node.getAttribute("message").ifBlank { node.textContent })
                                .trim()
                                .replace(Regex("\\s+"), " ")
                            val full = if (label.isNotBlank()) "$label: $text" else text
                            msg = full.take(100)
                            break@outer
                        }
                    }
                }
                msg
            }

            TestRun(
                name = name,
                timestamp = file.lastModified(),
                total = total,
                failures = failures,
                errors = errors,
                time = time,
                firstFailureMessage = firstFailureMsg
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
