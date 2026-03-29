package com.lacrearthur.claudio

import com.intellij.codeInsight.daemon.impl.HintRenderer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.wm.ToolWindowManager

private val log = Logger.getInstance("Claudio.SendInlayHints")

/**
 * Right-click or Tools > Send Inlay Hints to Claude
 * Reads inlay hints (type inference annotations the IDE adds inline) near the caret
 * and injects them into the Claudio input bar so Claude sees inferred types
 * without needing them written out in source.
 *
 * Output format:
 *   Inlay hints near caret (src/main/kotlin/MyService.kt, line 45, +-10 lines):
 *   line 42: val result: Result<String>
 *   line 43: val items: List<User>
 *   line 45: val config: MyConfig  (<- caret)
 *   line 47: val count: Int
 */
class SendInlayHintsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Collecting inlay hints...", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = ReadAction.compute<String?, Throwable> {
                    val document = editor.document
                    val inlayModel = editor.inlayModel
                    val caretOffset = editor.caretModel.offset
                    val caretLine = document.getLineNumber(caretOffset)

                    val windowLines = 10
                    val startLine = maxOf(0, caretLine - windowLines)
                    val endLine = minOf(document.lineCount - 1, caretLine + windowLines)
                    val startOffset = document.getLineStartOffset(startLine)
                    val endOffset = document.getLineEndOffset(endLine)

                    val inlays = inlayModel.getInlineElementsInRange(startOffset, endOffset) +
                                 inlayModel.getBlockElementsInRange(startOffset, endOffset)

                    data class HintEntry(val line: Int, val text: String)
                    val hints = mutableListOf<HintEntry>()

                    for (inlay in inlays) {
                        val text = when (val r = inlay.renderer) {
                            is HintRenderer -> r.text
                            else -> null
                        }
                        if (!text.isNullOrBlank()) {
                            val line = document.getLineNumber(inlay.offset) + 1
                            hints.add(HintEntry(line, text.trim()))
                        }
                    }

                    if (hints.isEmpty()) return@compute null

                    val rel = virtualFile?.path?.removePrefix(project.basePath ?: "")?.trimStart('/') ?: "unknown"
                    val caretLineDisplay = caretLine + 1

                    buildString {
                        appendLine("Inlay hints near caret ($rel, line $caretLineDisplay, +-${windowLines} lines):")
                        for (hint in hints.sortedBy { it.line }) {
                            val caretMarker = if (hint.line == caretLineDisplay) "  (<- caret)" else ""
                            appendLine("line ${hint.line}: ${hint.text}$caretMarker")
                        }
                    }.trimEnd()
                }

                if (result == null) {
                    log.warn("[Claudio] SendInlayHintsAction: no HintRenderer inlays found near caret")
                    com.intellij.notification.NotificationGroupManager.getInstance()
                        .getNotificationGroup("Claudio")
                        .createNotification(
                            "No inlay hints found near the caret (+-10 lines). " +
                            "Make sure type inference hints are enabled in Settings > Editor > Inlay Hints.",
                            com.intellij.notification.NotificationType.INFORMATION
                        )
                        .notify(project)
                    return
                }

                log.warn("[Claudio] SendInlayHintsAction: injecting inlay hints")
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
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
