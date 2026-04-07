package com.lacrearthur.claudio

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import javax.swing.*

class FreeTextQuestionDialog(
    project: Project,
    private val question: ParsedQuestion,
) : DialogWrapper(project) {

    private val textArea = JBTextArea(4, 40)

    init {
        title = "Claude: ${question.title}"
        setOKButtonText("Send")
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

        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.font = Font("JetBrains Mono", Font.PLAIN, 13)
        textArea.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(4, 6, 4, 6),
        )
        textArea.alignmentX = Component.LEFT_ALIGNMENT

        val scroll = JBScrollPane(textArea).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(420, 100)
        }
        panel.add(scroll)
        panel.preferredSize = Dimension(440, panel.preferredSize.height)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea
    fun getText(): String = textArea.text.trim()
    fun setText(text: String) { textArea.text = text }
}
