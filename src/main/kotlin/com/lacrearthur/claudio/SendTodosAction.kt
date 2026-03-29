package com.lacrearthur.claudio

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.PsiTodoSearchHelper

/**
 * Scans the project for all TODO/FIXME comments via the IDE index and injects them into Claudio's input bar.
 */
class SendTodosAction : AnAction("Send TODOs to Claude") {

    private data class TodoEntry(val file: String, val line: Int, val text: String)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val todos = collectTodos(project)
        if (todos.isEmpty()) return
        val prompt = buildPrompt(todos)
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
        tw.show {
            val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(prompt)
        }
    }

    private fun collectTodos(project: Project): List<TodoEntry> {
        val helper = PsiTodoSearchHelper.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val docManager = PsiDocumentManager.getInstance(project)
        val entries = mutableListOf<TodoEntry>()

        ProjectRootManager.getInstance(project).fileIndex.iterateContent { vFile ->
            if (!vFile.isDirectory) {
                val psiFile = psiManager.findFile(vFile) ?: return@iterateContent true
                val doc = docManager.getDocument(psiFile)
                for (todo in helper.findTodoItems(psiFile)) {
                    val line = doc?.getLineNumber(todo.textRange.startOffset)?.plus(1) ?: 0
                    val text = doc?.getText(todo.textRange)?.trim() ?: continue
                    val relativePath = vFile.path.removePrefix(project.basePath ?: "").removePrefix("/")
                    entries.add(TodoEntry(relativePath, line, text))
                }
            }
            true
        }

        return entries.sortedBy { it.file }.take(50)
    }

    private fun buildPrompt(todos: List<TodoEntry>): String {
        val sb = StringBuilder("Here are the project's TODO/FIXME items (${todos.size} total):\n\n")
        for (todo in todos) {
            sb.append("- ${todo.file}:${todo.line}: ${todo.text}\n")
        }
        sb.append("\nWhich of these should I tackle first, and how?")
        return sb.toString()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
