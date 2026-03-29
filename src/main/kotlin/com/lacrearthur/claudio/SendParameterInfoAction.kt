package com.lacrearthur.claudio

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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

private val log = Logger.getInstance("Claudio.SendParamInfo")

/**
 * Right-click anywhere in an expression -> "Send Caret Context to Claude"
 * Walks up the PSI tree from the element at caret and injects the element chain
 * (element, parent, grandparent, containing call/function) so Claude sees exactly
 * where the cursor is in the code structure without needing a description.
 *
 * Output format:
 *   Caret context in src/main/kotlin/MyService.kt:45:
 *     element:      "userService"
 *     parent (KtValueArgument): userService = userService
 *     grandparent (KtValueArgumentList): (userService = userService, config = config)
 *     context (KtCallExpression): MyClass(userService = userService, config = config)
 *     containing (KtNamedFunction): fun setupService()
 */
class SendParameterInfoAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Collecting caret context...", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = ReadAction.compute<String?, Throwable> {
                    val psiFile: PsiFile = PsiDocumentManager.getInstance(project)
                        .getPsiFile(editor.document) ?: return@compute null
                    val offset = editor.caretModel.offset
                    val element = psiFile.findElementAt(offset) ?: return@compute null

                    val line = editor.caretModel.logicalPosition.line + 1
                    val rel = virtualFile?.path?.removePrefix(project.basePath ?: "")?.trimStart('/') ?: "unknown"

                    buildContext(element, rel, line)
                }

                if (result.isNullOrBlank()) {
                    log.warn("[Claudio] SendParameterInfoAction: no PSI element at caret")
                    return
                }

                log.warn("[Claudio] SendParameterInfoAction: injecting caret context")
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
                toolWindow.show {
                    val content = toolWindow.contentManager.getContent(0) ?: return@show
                    val panel = content.component as? ClaudioTabbedPanel ?: return@show
                    panel.setInputText(result)
                }
            }
        })
    }

    private fun buildContext(element: PsiElement, relPath: String, line: Int): String {
        val sb = StringBuilder()
        sb.appendLine("Caret context in $relPath:$line:")

        fun label(el: PsiElement): String = el::class.simpleName ?: el.javaClass.simpleName

        fun truncate(text: String, max: Int = 100): String =
            if (text.length > max) text.take(max) + "..." else text

        // Level 0: leaf element at caret
        sb.appendLine("  element:      ${truncate(element.text.trim())}")

        // Levels 1-3: parent chain
        val levelNames = listOf("parent", "grandparent", "context")
        var current: PsiElement? = element.parent
        for (name in levelNames) {
            if (current == null || current is PsiFile) break
            sb.appendLine("  $name (${label(current)}): ${truncate(current.text.trim())}")
            current = current.parent
        }

        // Find the nearest named containing function/method (up to 20 levels)
        var walker: PsiElement? = element.parent
        var depth = 0
        while (walker != null && walker !is PsiFile && depth < 20) {
            val name = label(walker)
            if (name.contains("Function") || name.contains("Method") || name.contains("Fun")) {
                // Extract just the first line (signature) to keep it concise
                val firstLine = walker.text.trim().lines().firstOrNull() ?: walker.text.trim()
                sb.appendLine("  containing (${label(walker)}): ${truncate(firstLine)}")
                break
            }
            walker = walker.parent
            depth++
        }

        return sb.toString().trimEnd()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
