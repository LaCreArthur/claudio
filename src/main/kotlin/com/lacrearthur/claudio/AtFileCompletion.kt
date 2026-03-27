package com.lacrearthur.claudio

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private val log = Logger.getInstance("ClaudioAtFile")

/**
 * @file autocomplete for the input bar.
 *
 * Triggers when the user types @ anywhere in the input.
 * Filters by the text between @ and the cursor (no spaces).
 * Selecting a file inserts @relative/path at the cursor.
 *
 * File list is built lazily on first @ keystroke (preload() warms it earlier).
 * Excludes build dirs, .git, .idea, node_modules.
 */
class AtFileCompletion(
    private val project: Project,
    private val inputArea: JTextArea,
) {
    private var popup: JBPopup? = null
    @Volatile private var cachedFiles: List<String>? = null
    @Volatile private var replacing = false

    init {
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextChanged()
            override fun removeUpdate(e: DocumentEvent) = onTextChanged()
            override fun changedUpdate(e: DocumentEvent) {}
        })
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val p = popup ?: return
                if (!p.isVisible) return
                if (e.keyCode == KeyEvent.VK_ESCAPE) { p.cancel(); e.consume() }
            }
        })
    }

    /** Warm the file cache in the background. Call once on panel init. */
    fun preload() {
        Thread {
            cachedFiles = buildFileList()
            log.warn("[ATFILE] preloaded ${cachedFiles?.size} files")
        }.also { it.isDaemon = true }.start()
    }

    private fun onTextChanged() {
        if (replacing) return
        val text = inputArea.text
        val caret = inputArea.caretPosition.coerceAtMost(text.length)
        val beforeCaret = text.substring(0, caret)

        val atIdx = beforeCaret.lastIndexOf('@')
        if (atIdx == -1) { popup?.cancel(); return }

        val afterAt = beforeCaret.substring(atIdx + 1)
        // Cancel if there's a space or newline between @ and the cursor - not a file token
        if (afterAt.contains(' ') || afterAt.contains('\n')) { popup?.cancel(); return }

        val files = cachedFiles
        if (files == null) {
            // Cache not ready - trigger build and return; popup will appear on next keystroke
            preload()
            return
        }

        showPopup(atIdx, afterAt.lowercase(), files)
    }

    private fun showPopup(atIdx: Int, query: String, files: List<String>) {
        popup?.cancel()

        val filtered = if (query.isEmpty()) files.take(20)
                       else files.filter { it.lowercase().contains(query) }.take(20)
        if (filtered.isEmpty()) return

        popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(filtered)
            .setItemChosenCallback { path -> replaceAtQuery(atIdx, path) }
            .setRequestFocus(false)
            .setMovable(false)
            .setResizable(true)
            .createPopup()

        val loc = inputArea.locationOnScreen
        popup?.showInScreenCoordinates(
            inputArea,
            Point(loc.x, loc.y - (filtered.size.coerceAtMost(10) * 22)),
        )
    }

    private fun replaceAtQuery(atIdx: Int, path: String) {
        replacing = true
        try {
            val text = inputArea.text
            val caret = inputArea.caretPosition.coerceAtMost(text.length)
            val newText = text.substring(0, atIdx) + "@$path" + text.substring(caret)
            inputArea.text = newText
            inputArea.caretPosition = (atIdx + path.length + 1).coerceAtMost(newText.length)
        } finally {
            replacing = false
            popup?.cancel()
            popup = null
        }
    }

    private fun buildFileList(): List<String> {
        val base = File(project.basePath ?: return emptyList())
        val result = mutableListOf<String>()
        base.walkTopDown()
            .onEnter { dir ->
                val n = dir.name
                n != ".git" && n != "build" && n != ".gradle" && n != "node_modules" &&
                n != ".idea" && n != "out" && n != ".kotlin" && n != ".intellijPlatform"
            }
            .filter { it.isFile }
            .take(5000)
            .mapTo(result) { it.relativeTo(base).path }
        return result.sorted()
    }
}
