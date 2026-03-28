package com.lacrearthur.claudio

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Timer

class ClaudioStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private val timer = Timer(500) { statusBar?.updateWidget(ID) }

    companion object {
        const val ID = "ClaudioStatus"
    }

    override fun ID() = ID
    override fun getPresentation() = this

    override fun getText(): String {
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return ""
        val content = tw.contentManager.getContent(0) ?: return ""
        val panel = content.component as? ClaudioTabbedPanel ?: return ""
        val status = panel.currentStatus()
        val cost = panel.sessionCost()
        return if (cost > 0) "⚡ $status · \$${"%.4f".format(cost)}" else "⚡ $status"
    }

    override fun getTooltipText() = "Claudio session status - click to focus"
    override fun getAlignment() = 0f

    override fun getClickConsumer() = Consumer<MouseEvent> {
        ToolWindowManager.getInstance(project).getToolWindow("Claude")?.activate(null)
    }

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        timer.start()
    }

    override fun dispose() {
        timer.stop()
        statusBar = null
    }
}

class ClaudioStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = ClaudioStatusBarWidget.ID
    override fun getDisplayName() = "Claudio Status"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project) = ClaudioStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar) = true
}
