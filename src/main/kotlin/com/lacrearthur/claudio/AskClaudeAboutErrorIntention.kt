package com.lacrearthur.claudio

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * "Ask Claudio about this error" - appears in the editor right-click menu
 * only when the caret is on an error highlight.
 *
 * AnAction (not IntentionAction) so there is no settings submenu.
 */
class AskClaudeAboutErrorIntention : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isEnabledAndVisible =
            editor != null && project != null && errorAtCaret(editor, project) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val (offset, description) = errorAtCaret(editor, project) ?: return
        val line = editor.document.getLineNumber(offset) + 1
        val text = "Error in ${file.name}:$line\n$description\n\n"

        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
        (tw.contentManager.getContent(0)?.component as? ClaudePanel)?.appendToInput(text)
        tw.activate(null)
    }

    private fun errorAtCaret(editor: Editor, project: Project): Pair<Int, String>? {
        val offset = editor.caretModel.offset
        val model = DocumentMarkupModel.forDocument(editor.document, project, false) ?: return null
        return model.allHighlighters
            .filter { it.startOffset <= offset && offset <= it.endOffset }
            .mapNotNull { h ->
                val info = h.errorStripeTooltip as? HighlightInfo ?: return@mapNotNull null
                if (info.severity < HighlightSeverity.ERROR) return@mapNotNull null
                val desc = info.description ?: return@mapNotNull null
                Pair(h.startOffset, desc)
            }
            .firstOrNull()
    }
}
