package com.lacrearthur.claudio

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.Component
import java.awt.Dimension
import javax.swing.*

class PlanExitDialog(project: Project) : DialogWrapper(project) {

    init {
        title = "Exit Plan Mode"
        setOKButtonText("Start Coding")
        setCancelButtonText("Keep Planning")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val label = JLabel("<html><b>Claude is ready to start coding.</b><br><br>" +
                "Approve the plan and let Claude implement it?</html>")
        label.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(label)
        panel.preferredSize = Dimension(380, panel.preferredSize.height)
        return panel
    }
}
