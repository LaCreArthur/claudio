package com.lacrearthur.claudio

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.*
import java.io.File
import javax.swing.*

private val log = Logger.getInstance("Claudio")

class ClaudioTabbedPanel(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()), Disposable {

    private val tabbedPane = JTabbedPane()
    private var tabCounter = 0
    private var addingTab = false

    init {
        Disposer.register(parentDisposable, this)
        addTab()
        tabbedPane.addTab(ADD_TAB, JPanel())
        tabbedPane.addChangeListener {
            if (addingTab) return@addChangeListener
            val idx = tabbedPane.selectedIndex
            if (idx >= 0 && idx == tabbedPane.tabCount - 1 && tabbedPane.getTitleAt(idx) == ADD_TAB) {
                addTab()
            }
        }

        // Native ActionToolbar with icon-only buttons and automatic overflow
        val group = DefaultActionGroup().apply {
            add(PresetsAction())
            add(SessionsAction())
            add(RewindAction())
            addSeparator()
            add(BuildErrorsAction())
            add(SendDiffAction())
            add(AddDirectoryAction())
            add(ExportSessionAction())
            add(EditPresetsAction())
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ClaudioToolbar", group, true)
        toolbar.targetComponent = this

        val centerWrapper = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(tabbedPane, BorderLayout.CENTER)
        }
        add(centerWrapper, BorderLayout.CENTER)
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private inner class PresetsAction : AnAction("Presets", "Agent presets", AllIcons.Actions.Lightning) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val component = e.inputEvent?.component as? JComponent ?: return
            val customPresets = PresetStore.load().toMutableList()
            val allItems = (DEFAULT_PRESETS + customPresets).map { it.name } + listOf("---", "Edit presets...")
            com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                .createPopupChooserBuilder(allItems)
                .setItemChosenCallback { chosen ->
                    if (chosen == "Edit presets...") {
                        val mutable = PresetStore.load().toMutableList()
                        PresetEditorDialog(project, mutable).show()
                    } else if (chosen != "---") {
                        val preset = (DEFAULT_PRESETS + customPresets).firstOrNull { it.name == chosen } ?: return@setItemChosenCallback
                        addTab(model = preset.model)
                        val newIdx = tabbedPane.selectedIndex
                        if (newIdx >= 0) {
                            (tabbedPane.getTabComponentAt(newIdx) as? TabLabel)?.rename(preset.name)
                        }
                        appendToInput(preset.systemPrompt)
                    }
                }
                .createPopup()
                .showUnderneathOf(component)
        }
    }

    private inner class SessionsAction : AnAction("Sessions", "Resume a previous session", AllIcons.Vcs.History) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val component = e.inputEvent?.component as? JComponent ?: return
            showSessionPicker(component)
        }
    }

    private inner class RewindAction : AnAction("Rewind", "File checkpoints - view and restore", AllIcons.Actions.Rollback) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val component = e.inputEvent?.component as? JComponent ?: return
            showCheckpoints(component)
        }
    }

    private inner class BuildErrorsAction : AnAction("Build Errors", "Inject build errors into input bar", AllIcons.General.Error) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            (tabbedPane.selectedComponent as? ClaudePanel)?.injectBuildErrors()
        }
    }

    private inner class SendDiffAction : AnAction("Send Diff", "Send uncommitted diff to Claude", AllIcons.Actions.Diff) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            SendDiffToClaudeAction.perform(project)
        }
    }

    private inner class AddDirectoryAction : AnAction("Add Directory", "Add directory via /add-dir", AllIcons.Nodes.Folder) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(
                false, true, false, false, false, false
            ).withTitle("Add Directory")
            val baseDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, baseDir) { chosen ->
                setInputText("/add-dir ${chosen.path}")
            }
        }
    }

    private inner class ExportSessionAction : AnAction("Export Session", "Export session to Markdown", AllIcons.ToolbarDecorator.Export) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            (tabbedPane.selectedComponent as? ClaudePanel)?.exportSession()
        }
    }

    private inner class EditPresetsAction : AnAction("Edit Presets File", "Open presets.json", AllIcons.General.Settings) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val f = File(System.getProperty("user.home"), ".claudio/presets.json")
            f.parentFile?.mkdirs()
            if (!f.exists()) f.writeText("[]")
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
            if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    // ── Tab management ──────────────────────────────────────────────────────

    private fun addTab(model: String = "", resumeSessionId: String = "", tabName: String = "") {
        addingTab = true
        tabCounter++
        val panel = ClaudePanel(project, this, model, resumeSessionId)
        val name = tabName.ifEmpty { "Claude $tabCounter" }
        val insertAt = if (tabbedPane.tabCount > 0 && tabbedPane.getTitleAt(tabbedPane.tabCount - 1) == ADD_TAB)
            tabbedPane.tabCount - 1
        else
            tabbedPane.tabCount
        tabbedPane.insertTab(name, null, panel, null, insertAt)
        tabbedPane.setTabComponentAt(insertAt, TabLabel(name))
        tabbedPane.selectedIndex = insertAt
        addingTab = false
    }

    // ── Popovers ────────────────────────────────────────────────────────────

    private fun showSessionPicker(anchor: JComponent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val sessions = SessionLoader.loadSessions(project)
            ApplicationManager.getApplication().invokeLater {
                if (sessions.isEmpty()) {
                    JOptionPane.showMessageDialog(anchor, "No previous sessions found.", "Sessions", JOptionPane.INFORMATION_MESSAGE)
                    return@invokeLater
                }
                data class SessionItem(val label: String, val session: SessionInfo)
                val items = sessions.take(20).map { s ->
                    val time = SessionLoader.formatTimestamp(s.lastModified)
                    SessionItem("${s.name.take(50)}  ($time)", s)
                }
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(items)
                    .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { it.label })
                    .setItemChosenCallback { item ->
                        addTab(
                            resumeSessionId = item.session.sessionId,
                            tabName = item.session.name.take(30),
                        )
                    }
                    .createPopup()
                    .showUnderneathOf(anchor)
            }
        }
    }

    private fun showCheckpoints(anchor: JComponent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val checkpoints = SessionLoader.loadCheckpoints(project)
            val historyDir = SessionLoader.getHistoryDir(project)
            ApplicationManager.getApplication().invokeLater {
                if (checkpoints.isEmpty() || historyDir == null) {
                    JOptionPane.showMessageDialog(anchor, "No checkpoints found.", "Rewind", JOptionPane.INFORMATION_MESSAGE)
                    return@invokeLater
                }
                data class CheckpointItem(val label: String, val checkpoint: CheckpointInfo)
                val items = checkpoints.take(20).map { cp ->
                    val time = SessionLoader.formatTimestamp(cp.epochMillis)
                    val fileCount = cp.files.size
                    val preview = cp.files.take(3).joinToString(", ") { File(it.filePath).name }
                    CheckpointItem("$time  ($fileCount files: $preview)", cp)
                }
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(items)
                    .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { it.label })
                    .setItemChosenCallback { item -> showCheckpointDetail(item.checkpoint, historyDir) }
                    .createPopup()
                    .showUnderneathOf(anchor)
            }
        }
    }

    private fun showCheckpointDetail(cp: CheckpointInfo, historyDir: File) {
        val time = SessionLoader.formatTimestamp(cp.epochMillis)
        val listModel = DefaultListModel<CheckpointFile>()
        cp.files.forEach { listModel.addElement(it) }

        val fileList = JList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            setCellRenderer(object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ): java.awt.Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val f = value as? CheckpointFile ?: return this
                    text = "${File(f.filePath).name}  (v${f.version})"
                    return this
                }
            })
        }

        val viewBtn = JButton("View backup").apply {
            addActionListener {
                val sel = fileList.selectedValue ?: return@addActionListener
                val backupFile = File(historyDir, sel.backupFileName)
                if (!backupFile.exists()) return@addActionListener
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(backupFile)
                if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }
        val restoreBtn = JButton("Restore file").apply {
            addActionListener {
                val sel = fileList.selectedValue ?: return@addActionListener
                val backupFile = File(historyDir, sel.backupFileName)
                if (!backupFile.exists()) return@addActionListener
                val targetFile = File(sel.filePath)
                if (!targetFile.exists()) return@addActionListener
                val confirm = JOptionPane.showConfirmDialog(
                    tabbedPane,
                    "Restore ${File(sel.filePath).name} to v${sel.version}?",
                    "Restore",
                    JOptionPane.OK_CANCEL_OPTION,
                )
                if (confirm != JOptionPane.OK_OPTION) return@addActionListener
                val content = backupFile.readBytes()
                ApplicationManager.getApplication().runWriteAction {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetFile)
                    vf?.setBinaryContent(content)
                }
            }
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(viewBtn)
            add(restoreBtn)
        }

        val panel = JPanel(BorderLayout(0, 4)).apply {
            preferredSize = Dimension(400, 250)
            add(JScrollPane(fileList), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        JOptionPane.showMessageDialog(tabbedPane, panel, "Checkpoint - $time", JOptionPane.PLAIN_MESSAGE)
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun appendToInput(text: String) {
        (tabbedPane.selectedComponent as? ClaudePanel)?.appendToInput(text)
    }

    fun setInputText(text: String) {
        (tabbedPane.selectedComponent as? ClaudePanel)?.setInputText(text)
    }

    fun sendText(text: String) {
        (tabbedPane.selectedComponent as? ClaudePanel)?.sendText(text)
    }

    fun currentStatus(): String {
        val panel = tabbedPane.selectedComponent as? ClaudePanel ?: return "Off"
        return panel.currentStatus()
    }

    fun sessionCost(): Double {
        val panel = tabbedPane.selectedComponent as? ClaudePanel ?: return 0.0
        return panel.sessionCost()
    }

    fun getTabName(panel: ClaudePanel): String {
        val idx = tabbedPane.indexOfComponent(panel)
        return if (idx >= 0) tabbedPane.getTitleAt(idx) else "Claude"
    }

    override fun dispose() {}

    /** Tab label with inline rename on double-click. */
    private inner class TabLabel(name: String) : JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)) {
        private val label = JLabel(name)
        private val closeBtn = JButton("×").apply {
            font = MONO_11
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            toolTipText = "Close tab"
            preferredSize = Dimension(16, 16)
            addActionListener { closeThisTab() }
        }

        init {
            isOpaque = false
            add(label)
            add(closeBtn)
            label.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) startEdit()
                }
            })
        }

        private fun closeThisTab() {
            val realTabCount = tabbedPane.tabCount - 1
            if (realTabCount <= 1) return
            val idx = tabbedPane.indexOfTabComponent(this)
            if (idx < 0) return
            val panel = tabbedPane.getComponentAt(idx) as? ClaudePanel ?: return
            Disposer.dispose(panel)
            tabbedPane.removeTabAt(idx)
        }

        fun rename(newName: String) {
            label.text = newName
            val idx = tabbedPane.indexOfTabComponent(this)
            if (idx >= 0) tabbedPane.setTitleAt(idx, newName)
            revalidate()
            repaint()
        }

        private fun startEdit() {
            val idx = tabbedPane.indexOfTabComponent(this)
            if (idx < 0) return
            val field = JTextField(label.text, 10)
            var committed = false
            fun commit() {
                if (committed) return
                committed = true
                val name = field.text.trim().ifEmpty { label.text }
                label.text = name
                tabbedPane.setTitleAt(idx, name)
                remove(field)
                add(label)
                revalidate()
                repaint()
            }
            remove(label)
            add(field)
            revalidate()
            repaint()
            field.selectAll()
            field.requestFocusInWindow()
            field.addActionListener { commit() }
            field.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) { commit() }
            })
        }
    }

    companion object {
        private const val ADD_TAB = "+"
    }
}
