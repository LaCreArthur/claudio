package com.lacrearthur.claudio

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*

private val log = Logger.getInstance("ClaudioInlineDiff")

/** A changed hunk: range of lines in the "after" file that differ from "before". */
data class DiffHunk(val startLine: Int, val endLine: Int) // 0-based, inclusive

/**
 * Shows inline diff decorations in the editor:
 * - Green background on changed lines
 * - Keep/Undo buttons as after-line-end inlays on each hunk's first line
 * - Bottom floating toolbar with Keep All / Undo All / navigation
 */
class InlineDiffReviewer(
    private val project: Project,
    private val path: String,
    private val before: String,
    private val after: String,
    private val onKeepHunk: (DiffHunk) -> Unit,
    private val onUndoHunk: (DiffHunk) -> Unit,
    private val onKeepAll: () -> Unit,
    private val onUndoAll: () -> Unit,
    private val onDismiss: () -> Unit,
) : Disposable {

    private val highlighters = mutableListOf<RangeHighlighter>()
    private val inlays = mutableListOf<Inlay<*>>()
    private var toolbarPanel: JPanel? = null
    private var hunks: List<DiffHunk> = emptyList()
    private var currentHunkIndex = 0
    private var editor: Editor? = null

    private val addedBg = JBColor(Color(0x2E, 0x4E, 0x2E), Color(0x2E, 0x4E, 0x2E))

    fun show() {
        hunks = computeHunks(before, after)
        if (hunks.isEmpty()) { onDismiss(); return }

        // Open the file and get the editor
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        val descriptor = OpenFileDescriptor(project, vf, hunks[0].startLine, 0)
        val textEditor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true) ?: return
        editor = textEditor

        val markup = textEditor.markupModel
        val doc = textEditor.document

        // Highlight changed lines
        for (hunk in hunks) {
            for (line in hunk.startLine..minOf(hunk.endLine, doc.lineCount - 1)) {
                val attrs = TextAttributes().apply { backgroundColor = addedBg }
                val h = markup.addLineHighlighter(line, HighlighterLayer.SELECTION + 1, attrs)
                highlighters.add(h)
            }

            // Add Keep/Undo inlay at end of first line of hunk
            val offset = doc.getLineEndOffset(minOf(hunk.startLine, doc.lineCount - 1))
            val renderer = HunkButtonRenderer(hunk)
            val inlay = textEditor.inlayModel.addAfterLineEndElement(offset, true, renderer)
            if (inlay != null) inlays.add(inlay)
        }

        // Add floating toolbar at bottom
        addToolbar(textEditor)

        // Navigate to first hunk
        navigateToHunk(0)
    }

    private fun addToolbar(editor: Editor) {
        val toolbar = JPanel(FlowLayout(FlowLayout.CENTER, 6, 2)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(80, 80, 80), Color(80, 80, 80)), 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)
            )
            background = JBColor(Color(50, 50, 55), Color(50, 50, 55))
        }

        val keepAllBtn = makeToolbarButton("Keep All", Color(0x4C, 0xAF, 0x50)) { cleanupAndRun(onKeepAll) }
        val undoAllBtn = makeToolbarButton("Undo All", Color(0xF4, 0x43, 0x36)) { cleanupAndRun(onUndoAll) }
        val upBtn = makeToolbarButton("\u2191", null) { navigateToHunk(currentHunkIndex - 1) }
        val downBtn = makeToolbarButton("\u2193", null) { navigateToHunk(currentHunkIndex + 1) }
        val countLabel = JLabel("${currentHunkIndex + 1} of ${hunks.size}").apply {
            font = Font("JetBrains Mono", Font.PLAIN, 11)
            foreground = JBColor(Color(200, 200, 200), Color(200, 200, 200))
        }

        toolbar.add(keepAllBtn)
        toolbar.add(undoAllBtn)
        toolbar.add(upBtn)
        toolbar.add(countLabel)
        toolbar.add(downBtn)

        // Add as a glass pane overlay at the bottom of the editor scroll pane
        val scrollPane = editor.scrollingModel.visibleArea
        val editorComponent = editor.contentComponent
        val parent = editorComponent.parent?.parent // JScrollPane
        if (parent is JComponent) {
            toolbar.setBounds(
                scrollPane.width / 2 - 150,
                scrollPane.height - 40,
                300, 32
            )
            // Use layered pane approach
            SwingUtilities.invokeLater {
                val layered = SwingUtilities.getRootPane(editorComponent)?.layeredPane
                if (layered != null) {
                    val editorLoc = SwingUtilities.convertPoint(editorComponent, 0, 0, layered)
                    toolbar.setBounds(
                        editorLoc.x + editorComponent.width / 2 - 150,
                        editorLoc.y + editorComponent.visibleRect.height - 40,
                        300, 32
                    )
                    layered.add(toolbar, JLayeredPane.POPUP_LAYER)
                    layered.revalidate()
                    layered.repaint()
                    toolbarPanel = toolbar
                }
            }
        }
    }

    private fun navigateToHunk(index: Int) {
        val ed = editor ?: return
        if (hunks.isEmpty()) return
        currentHunkIndex = ((index % hunks.size) + hunks.size) % hunks.size
        val hunk = hunks[currentHunkIndex]
        ed.scrollingModel.scrollTo(
            com.intellij.openapi.editor.LogicalPosition(hunk.startLine, 0),
            com.intellij.openapi.editor.ScrollType.CENTER
        )
    }

    fun cleanup() {
        val ed = editor ?: return
        highlighters.forEach { ed.markupModel.removeHighlighter(it) }
        highlighters.clear()
        inlays.forEach { Disposer.dispose(it) }
        inlays.clear()
        toolbarPanel?.let {
            it.parent?.remove(it)
            it.parent?.repaint()
        }
        toolbarPanel = null
    }

    private fun cleanupAndRun(action: () -> Unit) {
        cleanup()
        action()
    }

    override fun dispose() {
        cleanup()
    }

    /** Renderer for Keep/Undo buttons at end of a hunk's first line. */
    private inner class HunkButtonRenderer(private val hunk: DiffHunk) : EditorCustomElementRenderer {
        private val keepRect = Rectangle()
        private val undoRect = Rectangle()
        private val btnHeight = 16
        private val btnWidth = 50

        override fun calcWidthInPixels(inlay: Inlay<*>): Int = btnWidth * 2 + 16

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.font = Font("JetBrains Mono", Font.BOLD, 10)

            val y = targetRegion.y + (targetRegion.height - btnHeight) / 2

            // Keep button
            keepRect.setBounds(targetRegion.x + 8, y, btnWidth, btnHeight)
            g2.color = Color(0x4C, 0xAF, 0x50)
            g2.fill(RoundRectangle2D.Float(keepRect.x.toFloat(), keepRect.y.toFloat(), keepRect.width.toFloat(), keepRect.height.toFloat(), 4f, 4f))
            g2.color = Color.WHITE
            val keepFm = g2.fontMetrics
            g2.drawString("Keep", keepRect.x + (keepRect.width - keepFm.stringWidth("Keep")) / 2, keepRect.y + keepFm.ascent + 1)

            // Undo button
            undoRect.setBounds(keepRect.x + btnWidth + 4, y, btnWidth, btnHeight)
            g2.color = Color(0xF4, 0x43, 0x36)
            g2.fill(RoundRectangle2D.Float(undoRect.x.toFloat(), undoRect.y.toFloat(), undoRect.width.toFloat(), undoRect.height.toFloat(), 4f, 4f))
            g2.color = Color.WHITE
            g2.drawString("Undo", undoRect.x + (undoRect.width - keepFm.stringWidth("Undo")) / 2, undoRect.y + keepFm.ascent + 1)

            g2.dispose()
        }
    }

    private fun makeToolbarButton(text: String, bg: Color?, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = Font("JetBrains Mono", Font.BOLD, 11)
            isFocusable = false
            foreground = Color.WHITE
            if (bg != null) {
                background = bg
                isOpaque = true
            } else {
                isOpaque = false
                isContentAreaFilled = false
            }
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { action() }
        }
    }

    companion object {
        /** Compute diff hunks (contiguous ranges of changed lines). */
        fun computeHunks(before: String, after: String): List<DiffHunk> {
            val oldLines = before.lines()
            val newLines = after.lines()

            // Simple line-by-line diff: find lines in "after" that differ from "before"
            val changedLines = mutableListOf<Int>()
            val maxLen = maxOf(oldLines.size, newLines.size)
            for (i in 0 until maxLen) {
                val oldLine = oldLines.getOrNull(i)
                val newLine = newLines.getOrNull(i)
                if (oldLine != newLine) changedLines.add(i)
            }

            // Group consecutive changed lines into hunks
            if (changedLines.isEmpty()) return emptyList()
            val hunks = mutableListOf<DiffHunk>()
            var start = changedLines[0]
            var end = start
            for (i in 1 until changedLines.size) {
                if (changedLines[i] == end + 1) {
                    end = changedLines[i]
                } else {
                    hunks.add(DiffHunk(start, end))
                    start = changedLines[i]
                    end = start
                }
            }
            hunks.add(DiffHunk(start, end))
            return hunks
        }
    }
}
