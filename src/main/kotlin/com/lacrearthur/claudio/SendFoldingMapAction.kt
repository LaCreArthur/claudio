package com.lacrearthur.claudio

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.wm.ToolWindowManager

private val log = Logger.getInstance("Claudio.SendFoldingMap")

/**
 * Right-click or Tools > Send Folding Map to Claude
 * Reads all collapsed fold regions in the current editor via FoldingModel
 * and injects them into the Claudio input bar so Claude knows which parts
 * of the file the developer considers noise vs signal.
 *
 * Output format:
 *   Code folding state for src/main/kotlin/MyService.kt:
 *   Collapsed regions (3):
 *     lines 42-89: { ... }   [private fun helperMethod()]
 *     lines 120-145: import ...
 *     lines 200-220: { ... }
 *
 *   Expanded regions (12 total - not shown)
 */
class SendFoldingMapAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Reading fold regions...", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = ReadAction.compute<String?, Throwable> {
                    val document = editor.document
                    val foldingModel = editor.foldingModel
                    val allRegions = foldingModel.allFoldRegions

                    if (allRegions.isEmpty()) return@compute null

                    val collapsed = allRegions.filter { !it.isExpanded }
                    val expandedCount = allRegions.size - collapsed.size

                    val rel = virtualFile?.path?.removePrefix(project.basePath ?: "")?.trimStart('/') ?: "unknown"

                    buildString {
                        appendLine("Code folding state for $rel:")

                        if (collapsed.isEmpty()) {
                            appendLine("No collapsed regions (all ${allRegions.size} regions are expanded)")
                            return@buildString
                        }

                        val capped = collapsed.take(20)
                        appendLine("Collapsed regions (${collapsed.size}${if (collapsed.size > 20) ", showing first 20" else ""}):")

                        for (region in capped) {
                            val startLine = document.getLineNumber(region.startOffset) + 1
                            val endLine = document.getLineNumber(region.endOffset) + 1
                            val placeholder = region.placeholderText.trim()

                            // Get the first line of the collapsed block for context
                            val lineStart = document.getLineStartOffset(startLine - 1)
                            val lineEnd = document.getLineEndOffset(startLine - 1)
                            val firstLineText = document.getText(TextRange(lineStart, lineEnd)).trim()

                            val contextSuffix = if (firstLineText.isNotBlank() && firstLineText != placeholder) {
                                "   [$firstLineText]"
                            } else ""

                            appendLine("  lines $startLine-$endLine: $placeholder$contextSuffix")
                        }

                        if (expandedCount > 0) {
                            append("\nExpanded regions ($expandedCount total - not shown)")
                        }
                    }.trimEnd()
                }

                if (result == null) {
                    log.info("[Claudio] SendFoldingMapAction: no fold regions in editor")
                    com.intellij.notification.NotificationGroupManager.getInstance()
                        .getNotificationGroup("Claudio")
                        .createNotification(
                            "No fold regions found in the current file.",
                            com.intellij.notification.NotificationType.INFORMATION
                        )
                        .notify(project)
                    return
                }

                log.info("[Claudio] SendFoldingMapAction: injecting folding map")
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
                toolWindow.show {
                    val content = toolWindow.contentManager.getContent(0) ?: return@show
                    val panel = content.component as? ClaudioTabbedPanel ?: return@show
                    panel.setInputText(result)
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
