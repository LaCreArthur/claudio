package com.lacrearthur.claudio

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.Component
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.*

class AskUserQuestionDialog(
    project: Project,
    private val question: ParsedQuestion,
) : DialogWrapper(project) {

    private var selectedIndex = 0
    private val freeTextField = JTextField(30)

    init {
        title = "Claude: ${question.title}"
        setOKButtonText("Answer")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        if (question.body.isNotBlank()) {
            val bodyLabel = JLabel("<html><p style='width:350px'>${question.body.replace("\n", "<br>")}</p></html>")
            bodyLabel.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(bodyLabel)
            panel.add(Box.createVerticalStrut(12))
        }

        val group = ButtonGroup()
        question.options.forEachIndexed { idx, opt ->
            val radio = JRadioButton(opt.label).apply {
                isSelected = idx == 0
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { selectedIndex = idx }
            }
            group.add(radio)
            panel.add(radio)

            if (opt.description.isNotBlank()) {
                val desc = JLabel(opt.description).apply {
                    foreground = UIManager.getColor("Component.infoForeground")
                    border = BorderFactory.createEmptyBorder(0, 24, 0, 0)
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                panel.add(desc)
            }

            if (opt.isFreeText) {
                val textPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    border = BorderFactory.createEmptyBorder(2, 24, 4, 0)
                    alignmentX = Component.LEFT_ALIGNMENT
                    add(freeTextField)
                }
                panel.add(textPanel)
                freeTextField.addFocusListener(object : FocusAdapter() {
                    override fun focusGained(e: FocusEvent?) {
                        radio.isSelected = true
                        selectedIndex = idx
                    }
                })
            }
            panel.add(Box.createVerticalStrut(4))
        }

        panel.preferredSize = Dimension(420, panel.preferredSize.height)
        return panel
    }

    fun getSelectedOptionIndex(): Int = selectedIndex
    fun getFreeText(): String = freeTextField.text.trim()
    fun isFreeTextSelected(): Boolean = question.options.getOrNull(selectedIndex)?.isFreeText == true

    fun selectFreeTextAndSetText(text: String) {
        val ftIndex = question.options.indexOfFirst { it.isFreeText }
        if (ftIndex >= 0) {
            selectedIndex = ftIndex
            freeTextField.text = text
        }
    }
}
