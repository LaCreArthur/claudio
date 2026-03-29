package com.lacrearthur.claudio

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint

/**
 * Injects all active XDebugger breakpoints (file, line, enabled state, condition) into the
 * Claudio input bar. Answers "where am I stopping and why" instantly.
 */
class SendBreakpointsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val breakpoints = manager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()

        if (breakpoints.isEmpty()) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
            toolWindow.show {
                val content = toolWindow.contentManager.getContent(0) ?: return@show
                val panel = content.component as? ClaudioTabbedPanel ?: return@show
                panel.setInputText("No breakpoints are currently set in this project.")
            }
            return
        }

        val basePath = project.basePath ?: ""
        val lines = breakpoints.mapIndexed { index, bp ->
            val file = bp.fileUrl.removePrefix("file://")
            val relPath = file.removePrefix(basePath).trimStart('/')
            val line = bp.line + 1
            val enabled = if (bp.isEnabled) "" else " [DISABLED]"
            val condition = bp.conditionExpression?.expression?.let { " if ($it)" } ?: ""
            "${index + 1}. $relPath:$line$enabled$condition"
        }

        val prompt = "Breakpoints (${breakpoints.size}):\n${lines.joinToString("\n")}"

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claudio") ?: return
        toolWindow.show {
            val content = toolWindow.contentManager.getContent(0) ?: return@show
            val panel = content.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(prompt)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
