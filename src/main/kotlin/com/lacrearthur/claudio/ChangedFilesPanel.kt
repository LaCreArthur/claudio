package com.lacrearthur.claudio

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Collapsible panel showing files changed by Claude, with click-to-diff.
 * Sits between the terminal and input bar.
 *
 * Layout:
 *   [v] 2 files changed  +5 -2       [Keep All] [Undo All]
 *       SorollaEventBridge.cs  assets/_boatrunner/dev/...  +1 -0
 *       PlayerController.cs    assets/_boatrunner/...      +4 -2
 */
class ChangedFilesPanel(
    private val project: Project,
    private val hookServer: HookServer,
) : JPanel(BorderLayout()) {

    private val fileListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val headerLabel = JLabel()
    private var collapsed = false

    init {
        isVisible = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor(Color(60, 60, 60), Color(60, 60, 60)), 1, 0, 0, 0),
            JBUI.Borders.empty(6, 10, 6, 10)
        )

        val header = JPanel(BorderLayout()).apply { isOpaque = false }

        val toggleBtn = JLabel("\u25BC ").apply {
            font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(10))
            foreground = JBColor.GRAY
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { toggleCollapse() }
            })
        }

        headerLabel.font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))
        headerLabel.foreground = JBColor(Color(200, 200, 200), Color(200, 200, 200))

        val leftHeader = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(toggleBtn)
            add(headerLabel)
        }

        val keepAllBtn = makeSmallButton("Keep All") {
            hookServer.changedFiles.clear()
            refresh()
        }
        val undoAllBtn = makeSmallButton("Undo All") {
            for ((path, pair) in hookServer.changedFiles) {
                try {
                    java.io.File(path).writeText(pair.first)
                    LocalFileSystem.getInstance().findFileByPath(path)?.refresh(false, false)
                } catch (_: Exception) {}
            }
            hookServer.changedFiles.clear()
            refresh()
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(keepAllBtn)
            add(undoAllBtn)
        }

        header.add(leftHeader, BorderLayout.WEST)
        header.add(buttonPanel, BorderLayout.EAST)
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { toggleCollapse() }
        })
        header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        add(header, BorderLayout.NORTH)
        add(fileListPanel, BorderLayout.CENTER)

        hookServer.onFileChanged = { refresh() }
    }

    fun refresh() {
        val files = hookServer.changedFiles
        if (files.isEmpty()) {
            isVisible = false
            revalidate()
            repaint()
            return
        }

        var totalAdded = 0
        var totalRemoved = 0
        for ((_, pair) in files) {
            val (added, removed) = countChanges(pair.first, pair.second)
            totalAdded += added
            totalRemoved += removed
        }

        headerLabel.text = "<html>${files.size} file${if (files.size > 1) "s" else ""} changed &nbsp;" +
            "<font color='#4caf50'>+$totalAdded</font> <font color='#f44336'>-$totalRemoved</font></html>"

        fileListPanel.removeAll()
        if (!collapsed) {
            val basePath = project.basePath ?: ""
            for ((path, pair) in files) {
                val row = createFileRow(path, basePath, pair)
                fileListPanel.add(row)
            }
        }

        isVisible = true
        revalidate()
        repaint()
    }

    private fun createFileRow(path: String, basePath: String, pair: Pair<String, String>): JPanel {
        val fileName = path.substringAfterLast("/")
        val relDir = path.removePrefix(basePath).trimStart('/').substringBeforeLast("/", "")
        val (added, removed) = countChanges(pair.first, pair.second)
        val truncDir = if (relDir.length > 40) "...${relDir.takeLast(37)}" else relDir

        val row = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 16, 2, 0)
        }
        val gbc = GridBagConstraints()

        val nameLabel = JLabel(fileName).apply {
            font = Font("JetBrains Mono", Font.BOLD, JBUI.scale(11))
            foreground = JBColor(Color(220, 220, 220), Color(220, 220, 220))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val dirLabel = JLabel("  $truncDir  ").apply {
            font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(9))
            foreground = JBColor.GRAY
        }
        val statsLabel = JLabel("<html><font color='#4caf50'>+$added</font>&nbsp;<font color='#f44336'>-$removed</font></html>").apply {
            font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(10))
        }
        val keepBtn = makeSmallButton("Keep") {
            hookServer.changedFiles.remove(path)
            refresh()
        }
        val undoBtn = makeSmallButton("Undo") {
            try {
                java.io.File(path).writeText(pair.first)
                LocalFileSystem.getInstance().findFileByPath(path)?.refresh(false, false)
            } catch (_: Exception) {}
            hookServer.changedFiles.remove(path)
            refresh()
        }

        // Name (clickable) | dir (fills space) | stats | Keep | Undo
        gbc.gridy = 0; gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.WEST
        gbc.gridx = 0; gbc.weightx = 0.0; row.add(nameLabel, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; row.add(dirLabel, gbc)
        gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; row.add(statsLabel, gbc)
        gbc.gridx = 3; gbc.insets = Insets(0, 4, 0, 0); row.add(keepBtn, gbc)
        gbc.gridx = 4; gbc.insets = Insets(0, 2, 0, 0); row.add(undoBtn, gbc)

        // Click file name to open diff tab
        nameLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { showDiff(path, pair) }
            override fun mouseEntered(e: MouseEvent) {
                nameLabel.foreground = JBColor(Color(100, 160, 255), Color(100, 160, 255))
            }
            override fun mouseExited(e: MouseEvent) {
                nameLabel.foreground = JBColor(Color(220, 220, 220), Color(220, 220, 220))
            }
        })

        return row
    }

    private fun showDiff(path: String, pair: Pair<String, String>) {
        val fileName = path.substringAfterLast("/")
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val factory = DiffContentFactory.getInstance()
        val before = factory.create(project, pair.first, fileType)
        val after = factory.create(project, pair.second, fileType)
        val request = SimpleDiffRequest("Claude: $fileName", before, after, "Original", "Modified")
        val chain = SimpleDiffRequestChain(request)
        val diffFile = ChainDiffVirtualFile(chain, "Claude: $fileName")
        FileEditorManager.getInstance(project).openFile(diffFile, true)
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        refresh()
    }

    private fun countChanges(before: String, after: String): Pair<Int, Int> {
        val oldLines = before.lines()
        val newLines = after.lines()
        val oldSet = oldLines.toSet()
        val newSet = newLines.toSet()
        val added = newLines.count { it !in oldSet }
        val removed = oldLines.count { it !in newSet }
        return Pair(added, removed)
    }

    private fun makeSmallButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(10))
            isFocusable = false
            putClientProperty("JButton.buttonType", "roundRect")
            addActionListener { action() }
        }
    }
}
