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
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

private val log = Logger.getInstance("Claudio.SendHierarchy")

private const val MAX_INHERITORS = 30

/**
 * Right-click any class or interface -> "Send Hierarchy to Claude"
 * Uses ReferencesSearch to find all classes/interfaces that extend or implement
 * the target type, collects up to 30 as file:line references, and injects
 * the result into the Claudio input bar.
 *
 * Uses ReferencesSearch (platform API) rather than ClassInheritorsSearch
 * (Java plugin API) so it works in both IDEA and Rider.
 * Inheritance references are identified by checking whether the reference
 * text appears in a supertype-list context (extends/implements keywords in
 * the surrounding PSI text).
 */
class SendHierarchyAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Finding inheritors...", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = ReadAction.compute<String?, Throwable> {
                    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                        ?: return@compute null
                    val offset = editor.caretModel.offset
                    val element = psiFile.findElementAt(offset) ?: return@compute null
                    val target = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
                        ?: return@compute null

                    val symbolName = target.name ?: target.text.take(40)
                    val scope = GlobalSearchScope.projectScope(project)

                    val refs = try {
                        ReferencesSearch.search(target, scope).findAll()
                    } catch (ex: Exception) {
                        log.warn("[Claudio] SendHierarchyAction: search failed", ex)
                        return@compute null
                    }

                    if (refs.isEmpty()) {
                        return@compute "NO_INHERITORS:$symbolName"
                    }

                    val basePath = project.basePath ?: ""

                    // Filter for inheritance references: the reference appears in a
                    // supertype context. We check the parent PSI node text for
                    // extends/implements keywords, which covers Java, Kotlin, C#, etc.
                    val inheritorRefs = refs.filter { ref ->
                        val refElement = ref.element ?: return@filter false
                        // Walk up two levels to catch the supertype list node
                        val parent1 = refElement.parent
                        val parent2 = parent1?.parent
                        val parent3 = parent2?.parent
                        val nodeTexts = listOfNotNull(parent1?.text, parent2?.text, parent3?.text)
                        // Kotlin: ": SuperType" or ", SuperType" inside class header
                        // Java: "extends Foo" or "implements Foo, Bar"
                        // C#: ": IFoo, Bar"
                        nodeTexts.any { txt ->
                            txt.contains(" extends ") ||
                            txt.contains(" implements ") ||
                            txt.contains(": ") ||
                            txt.trimStart().startsWith(": ")
                        }
                    }

                    if (inheritorRefs.isEmpty()) {
                        return@compute "NO_INHERITORS:$symbolName"
                    }

                    val lines = StringBuilder()
                    val count = inheritorRefs.size
                    val showing = minOf(count, MAX_INHERITORS)
                    lines.appendLine("Hierarchy of $symbolName ($count implementors${if (count > MAX_INHERITORS) ", showing first $MAX_INHERITORS" else ""}):")

                    var idx = 0
                    for (ref in inheritorRefs) {
                        if (idx >= MAX_INHERITORS) break
                        val refElement = ref.element ?: continue
                        val containingFile = refElement.containingFile ?: continue
                        val virtualFile = containingFile.virtualFile ?: continue
                        val filePath = virtualFile.path
                        val relativePath = filePath.removePrefix(basePath).removePrefix("/")

                        val doc = PsiDocumentManager.getInstance(project).getDocument(containingFile)
                        val lineIdx = doc?.getLineNumber(refElement.textOffset) ?: continue
                        val lineNum = lineIdx + 1

                        // Find the containing named element (the class that extends/implements)
                        val containingClass = PsiTreeUtil.getParentOfType(refElement, PsiNamedElement::class.java)
                        val inheritorName = containingClass?.name ?: containingFile.name

                        idx++
                        lines.appendLine("$idx. $inheritorName - $relativePath:$lineNum")
                    }

                    lines.toString().trimEnd()
                }

                when {
                    result == null -> {
                        Notifications.Bus.notify(
                            Notification(
                                "Claudio",
                                "No element found",
                                "Place the caret on a class or interface declaration to find its inheritors.",
                                NotificationType.WARNING
                            ),
                            project
                        )
                    }
                    result.startsWith("NO_INHERITORS:") -> {
                        val sym = result.removePrefix("NO_INHERITORS:")
                        Notifications.Bus.notify(
                            Notification(
                                "Claudio",
                                "No inheritors found",
                                "No classes or interfaces extending '$sym' found in the project scope.",
                                NotificationType.INFORMATION
                            ),
                            project
                        )
                    }
                    else -> {
                        log.warn("[Claudio] SendHierarchyAction: injecting ${result.length} chars")
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
