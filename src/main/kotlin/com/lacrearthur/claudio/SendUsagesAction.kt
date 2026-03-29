package com.lacrearthur.claudio

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

private val log = Logger.getInstance("Claudio.SendUsages")

private const val MAX_USAGES = 50

/**
 * Right-click any symbol in the editor -> "Send Usages to Claude"
 * Runs a project-scope ReferencesSearch on the element at caret,
 * collects up to 50 usage sites as file:line references with line text,
 * and injects the result into the Claudio input bar.
 */
class SendUsagesAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Finding usages...", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = ReadAction.compute<String?, Throwable> {
                    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                        ?: return@compute null
                    val offset = editor.caretModel.offset
                    val element = psiFile.findElementAt(offset) ?: return@compute null
                    val target = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
                        ?: element

                    val symbolName = (target as? PsiNamedElement)?.name ?: target.text.take(40)
                    val scope = GlobalSearchScope.projectScope(project)

                    val refs = try {
                        ReferencesSearch.search(target, scope).findAll()
                    } catch (ex: Exception) {
                        log.warn("[Claudio] SendUsagesAction: search failed", ex)
                        return@compute null
                    }

                    if (refs.isEmpty()) {
                        return@compute "NO_USAGES:$symbolName"
                    }

                    val basePath = project.basePath ?: ""
                    val lines = StringBuilder()
                    lines.appendLine("Usages of $symbolName (${refs.size} results${if (refs.size > MAX_USAGES) ", showing first $MAX_USAGES" else ""}):")

                    var count = 0
                    for (ref in refs) {
                        if (count >= MAX_USAGES) break
                        val refElement = ref.element ?: continue
                        val containingFile = refElement.containingFile ?: continue
                        val virtualFile = containingFile.virtualFile ?: continue
                        val filePath = virtualFile.path
                        val relativePath = filePath.removePrefix(basePath).removePrefix("/")

                        val doc = PsiDocumentManager.getInstance(project).getDocument(containingFile)
                        val lineIdx = doc?.getLineNumber(refElement.textOffset) ?: continue
                        val lineNum = lineIdx + 1
                        val lineText = doc.getText(
                            TextRange(doc.getLineStartOffset(lineIdx), doc.getLineEndOffset(lineIdx))
                        ).trim()

                        count++
                        lines.appendLine("$count. $relativePath:$lineNum - $lineText")
                    }

                    lines.toString().trimEnd()
                }

                when {
                    result == null -> {
                        Notifications.Bus.notify(
                            Notification("Claudio", "No element found", "Place the caret on a symbol to find its usages.", NotificationType.WARNING),
                            project
                        )
                    }
                    result.startsWith("NO_USAGES:") -> {
                        val sym = result.removePrefix("NO_USAGES:")
                        Notifications.Bus.notify(
                            Notification("Claudio", "No usages found", "No usages of '$sym' found in the project scope.", NotificationType.INFORMATION),
                            project
                        )
                    }
                    else -> {
                        log.warn("[Claudio] SendUsagesAction: injecting ${result.length} chars")
                        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
                        toolWindow.show {
                            val content = toolWindow.contentManager.getContent(0) ?: return@show
                            val panel = content.component as? ClaudioTabbedPanel ?: return@show
                            panel.setInputText(result)
                        }
                    }
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
