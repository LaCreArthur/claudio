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

private val log = Logger.getInstance("Claudio.SendCallHierarchy")

private const val MAX_CALLERS = 50

/**
 * Right-click any function or method -> "Send Call Hierarchy to Claude"
 * Runs a project-scope ReferencesSearch on the element at caret,
 * collects up to 50 call sites as file:line references with line text,
 * and injects the result into the Claudio input bar.
 * Answers "what breaks if I change this" before a refactor.
 */
class SendCallHierarchyAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Finding callers...", false) {
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
                        log.warn("[Claudio] SendCallHierarchyAction: search failed", ex)
                        return@compute null
                    }

                    if (refs.isEmpty()) {
                        return@compute "NO_CALLERS:$symbolName"
                    }

                    val basePath = project.basePath ?: ""
                    val lines = StringBuilder()
                    lines.appendLine("Callers of $symbolName (${refs.size} results${if (refs.size > MAX_CALLERS) ", showing first $MAX_CALLERS" else ""}):")

                    var count = 0
                    for (ref in refs) {
                        if (count >= MAX_CALLERS) break
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
                            Notification("Claudio", "No element found", "Place the caret on a function or method to find its callers.", NotificationType.WARNING),
                            project
                        )
                    }
                    result.startsWith("NO_CALLERS:") -> {
                        val sym = result.removePrefix("NO_CALLERS:")
                        Notifications.Bus.notify(
                            Notification("Claudio", "No callers found", "No callers of '$sym' found in the project scope.", NotificationType.INFORMATION),
                            project
                        )
                    }
                    else -> {
                        log.warn("[Claudio] SendCallHierarchyAction: injecting ${result.length} chars")
                        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
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
