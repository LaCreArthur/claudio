package com.lacrearthur.claudio

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

internal class PresetEditorDialog(
    project: Project,
    private val customPresets: MutableList<ClaudePreset>,
) : DialogWrapper(project, true) {

    private val listModel = DefaultListModel<String>()
    private val presetList = JBList(listModel)
    private val nameField = JBTextField()
    private val promptArea = JBTextArea(8, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
    }
    private val modelOptions = arrayOf("", "claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5-20251001")
    private val modelCombo = ComboBox(modelOptions).apply {
        renderer = SimpleListCellRenderer.create("") { if (it == "") "Default" else it }
    }
    private val saveBtn = JButton("Save")

    private val allPresets: List<ClaudePreset> get() = DEFAULT_PRESETS + customPresets
    private var selectedIndex = -1

    init {
        title = "Edit Presets"
        init()
        rebuildList()
        presetList.addListSelectionListener {
            if (!it.valueIsAdjusting) onSelectionChanged()
        }
        saveBtn.isEnabled = false
        modelCombo.isEnabled = false
        saveBtn.addActionListener { onSave() }
    }

    override fun createCenterPanel(): JComponent {
        val listPanel = ToolbarDecorator.createDecorator(presetList)
            .setAddAction { onAdd() }
            .setRemoveAction { onDelete() }
            .setRemoveActionUpdater { selectedIndex >= DEFAULT_PRESETS.size }
            .disableUpDownActions()
            .createPanel().apply {
                preferredSize = Dimension(180, 0)
            }

        val rightPanel = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
            val topFields = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                val namePanel = JPanel(BorderLayout(4, 0)).apply {
                    add(JLabel("Name:"), BorderLayout.WEST)
                    add(nameField, BorderLayout.CENTER)
                }
                add(namePanel)
                add(Box.createVerticalStrut(4))
                val modelPanel = JPanel(BorderLayout(4, 0)).apply {
                    add(JLabel("Model:"), BorderLayout.WEST)
                    add(modelCombo, BorderLayout.CENTER)
                }
                add(modelPanel)
                add(Box.createVerticalStrut(4))
                add(saveBtn)
            }
            add(topFields, BorderLayout.NORTH)
            add(JLabel("System prompt:"), BorderLayout.CENTER)
            add(JBScrollPane(promptArea), BorderLayout.SOUTH)
        }

        val split = JPanel(BorderLayout(8, 0))
        split.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        split.add(listPanel, BorderLayout.WEST)
        split.add(rightPanel, BorderLayout.CENTER)
        return split
    }

    private fun rebuildList() {
        listModel.clear()
        for (p in DEFAULT_PRESETS) listModel.addElement("${p.name} (built-in)")
        for (p in customPresets) listModel.addElement(p.name)
    }

    private fun onSelectionChanged() {
        selectedIndex = presetList.selectedIndex
        if (selectedIndex < 0) {
            nameField.text = ""
            promptArea.text = ""
            nameField.isEditable = false
            promptArea.isEditable = false
            modelCombo.selectedItem = ""
            modelCombo.isEnabled = false
            saveBtn.isEnabled = false
            return
        }
        val preset = allPresets[selectedIndex]
        nameField.text = preset.name
        promptArea.text = preset.systemPrompt
        modelCombo.selectedItem = preset.model
        val isBuiltIn = selectedIndex < DEFAULT_PRESETS.size
        nameField.isEditable = !isBuiltIn
        promptArea.isEditable = !isBuiltIn
        modelCombo.isEnabled = !isBuiltIn
        saveBtn.isEnabled = !isBuiltIn
    }

    private fun onAdd() {
        val name = "New Preset ${customPresets.size + 1}"
        customPresets.add(ClaudePreset(name, "", ""))
        rebuildList()
        val newIdx = DEFAULT_PRESETS.size + customPresets.size - 1
        presetList.selectedIndex = newIdx
        nameField.requestFocusInWindow()
        nameField.selectAll()
    }

    private fun onSave() {
        val idx = selectedIndex
        if (idx < DEFAULT_PRESETS.size) return
        val customIdx = idx - DEFAULT_PRESETS.size
        val newName = nameField.text.trim().ifEmpty { customPresets[customIdx].name }
        val newPrompt = promptArea.text
        val newModel = (modelCombo.selectedItem as? String) ?: ""
        customPresets[customIdx] = ClaudePreset(newName, newPrompt, newModel)
        rebuildList()
        presetList.selectedIndex = idx
    }

    private fun onDelete() {
        val idx = selectedIndex
        if (idx < DEFAULT_PRESETS.size) return
        val customIdx = idx - DEFAULT_PRESETS.size
        customPresets.removeAt(customIdx)
        rebuildList()
        val newSel = minOf(idx, listModel.size - 1)
        if (newSel >= 0) presetList.selectedIndex = newSel
    }

    override fun doOKAction() {
        if (selectedIndex >= DEFAULT_PRESETS.size) onSave()
        PresetStore.save(customPresets)
        super.doOKAction()
    }
}
