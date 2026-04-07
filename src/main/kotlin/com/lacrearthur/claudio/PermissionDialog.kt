package com.lacrearthur.claudio

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import javax.swing.*

data class PermissionRequest(
    val action: String,
    val detail: String,
)

enum class PermissionChoice { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

class PermissionDialog(
    project: Project,
    private val request: PermissionRequest,
) : DialogWrapper(project) {

    private var choice = PermissionChoice.ALLOW_ONCE
    private val rememberCheckbox = JBCheckBox("Remember for this project")

    init {
        title = "Claude Permission Request"
        setOKButtonText("Allow Once")
        setCancelButtonText("Deny")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val heading = JLabel("<html><b>Claude wants to ${request.action}</b></html>")
        heading.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(heading)

        if (request.detail.isNotBlank()) {
            panel.add(Box.createVerticalStrut(8))
            val detail = JBTextArea(request.detail).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = Font("JetBrains Mono", Font.PLAIN, 12)
                background = UIManager.getColor("Panel.background")
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6),
                )
                alignmentX = Component.LEFT_ALIGNMENT
            }
            panel.add(detail)
        }

        panel.add(Box.createVerticalStrut(12))

        val group = ButtonGroup()
        listOf(
            "Allow once"                  to PermissionChoice.ALLOW_ONCE,
            "Always allow for this session" to PermissionChoice.ALLOW_ALWAYS,
            "Deny"                        to PermissionChoice.DENY,
        ).forEachIndexed { idx, (label, value) ->
            val radio = JRadioButton(label).apply {
                isSelected = idx == 0
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { choice = value }
            }
            group.add(radio)
            panel.add(radio)
        }

        panel.add(Box.createVerticalStrut(8))
        rememberCheckbox.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(rememberCheckbox)

        panel.preferredSize = Dimension(440, panel.preferredSize.height)
        return panel
    }

    fun getChoice(): PermissionChoice = choice
    fun setChoice(c: PermissionChoice) { choice = c }
    fun isRememberChecked(): Boolean = rememberCheckbox.isSelected

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)
    override fun doOKAction() { super.doOKAction() }
    override fun doCancelAction() { choice = PermissionChoice.DENY; super.doCancelAction() }
}
