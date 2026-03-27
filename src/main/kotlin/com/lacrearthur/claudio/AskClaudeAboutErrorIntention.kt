package com.lacrearthur.claudio

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile

/**
 * Appears in the Alt+Enter menu when the caret is on an error.
 * Appends the error + file:line header to the Claude input bar and opens the panel.
 *
 * This is the moat feature: no terminal or standalone app can hook into Alt+Enter.
 */
class AskClaudeAboutErrorIntention : IntentionAction {

    override fun getText() = "Ask Claude about this error"
    override fun getFamilyName() = "Claude"
    override fun startInWriteAction() = false

    // No diff preview - we're not modifying the document
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        editor ?: return false
        return errorAtCaret(editor, project) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        editor ?: return
        file ?: return
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
