@file:Suppress("DEPRECATION")

package com.lacrearthur.claudio

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager

private val log = Logger.getInstance("Claudio.SendDoc")

/**
 * Right-click any symbol in the editor -> "Send Documentation to Claude"
 * Fetches the KDoc/Javadoc for the symbol at caret via DocumentationManager,
 * strips HTML, caps at 200 lines, and injects the result into the Claudio input bar.
 */
class SendDocumentationAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val symbolName = file?.name ?: "symbol"

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching documentation...", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = ReadAction.compute<String?, Throwable> {
                    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@compute null
                    val manager = DocumentationManager.getInstance(project)
                    val target = manager.findTargetElement(editor, psiFile) ?: return@compute null
                    val originalElement = psiFile.findElementAt(editor.caretModel.offset)
                    manager.generateDocumentation(target, originalElement, false)
                }

                if (result.isNullOrBlank()) {
                    log.warn("[Claudio] SendDocumentationAction: no documentation found at caret")
                    Notifications.Bus.notify(
                        Notification("Claudio", "No documentation found", "No documentation available for the symbol at caret.", NotificationType.WARNING),
                        project
                    )
                    return
                }

                val stripped = stripHtml(result)
                val lines = stripped.lines()
                val capped = if (lines.size > 200) {
                    lines.take(200).joinToString("\n") + "\n(truncated at 200 lines)"
                } else {
                    stripped
                }

                // Derive a display name from the first non-blank line or fallback to file name
                val firstLine = capped.lines().firstOrNull { it.isNotBlank() } ?: symbolName
                val label = if (firstLine.length <= 80) firstLine else symbolName

                val prompt = "Documentation for $label:\n$capped"
                log.warn("[Claudio] SendDocumentationAction: injecting ${capped.length} chars")

                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
                toolWindow.show {
                    val content = toolWindow.contentManager.getContent(0) ?: return@show
                    val panel = content.component as? ClaudioTabbedPanel ?: return@show
                    panel.setInputText(prompt)
                }
            }
        })
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")   // strip tags
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\n{3,}"), "\n\n") // collapse excess blank lines
            .trim()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
