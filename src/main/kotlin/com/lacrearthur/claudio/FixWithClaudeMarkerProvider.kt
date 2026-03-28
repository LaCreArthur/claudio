package com.lacrearthur.claudio

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import java.awt.event.MouseEvent

/**
 * Gutter lightning bolt on error/warning lines.
 * Click -> sends "Fix this error: <file>:<line>: <message>" to the Claudio input bar.
 */
class FixWithClaudeMarkerProvider : LineMarkerProvider {

    private val icon = IconLoader.getIcon("/icons/claudio-mark.svg", FixWithClaudeMarkerProvider::class.java)

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only fire on the first meaningful token of each line to avoid duplicate markers
        val parent = element.parent ?: return null
        if (element is PsiWhiteSpace) return null
        val prevSibling = element.prevSibling
        if (prevSibling != null && prevSibling !is PsiWhiteSpace) return null

        val project = element.project
        val file = element.containingFile ?: return null
        val document = file.viewProvider.document ?: return null
        val lineNumber = document.getLineNumber(element.textRange.startOffset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)

        val model = DocumentMarkupModel.forDocument(document, project, false) ?: return null
        val hit = model.allHighlighters
            .filter { h -> h.startOffset >= lineStart && h.startOffset <= lineEnd }
            .mapNotNull { h ->
                val info = h.errorStripeTooltip as? HighlightInfo ?: return@mapNotNull null
                if (info.severity < HighlightSeverity.WARNING) return@mapNotNull null
                info.description
            }
            .firstOrNull() ?: return null

        val displayLine = lineNumber + 1
        val fileName = file.name

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { _: PsiElement -> "Fix with Claudio: $hit" },
            { _: MouseEvent, _: PsiElement ->
                val text = "Fix this error in $fileName:$displayLine\n$hit\n\n"
                val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return@LineMarkerInfo
                (tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel)?.appendToInput(text)
                tw.activate(null)
            },
            GutterIconRenderer.Alignment.LEFT,
            { "Fix with Claudio" }
        )
    }
}
