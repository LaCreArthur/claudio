package com.lacrearthur.claudio

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.xdebugger.XDebuggerManager

/**
 * When the debugger is paused at a breakpoint, injects the current pause location into the Claudio input bar.
 * Shown only when a debug session is suspended. Works from the debugger toolbar and Tools menu.
 */
class SendDebuggerContextAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = XDebuggerManager.getInstance(project).currentSession ?: return
        if (!session.isSuspended) return

        val frame = session.currentStackFrame
        val position = frame?.sourcePosition
        val posStr = if (position != null) {
            val rel = position.file.path.removePrefix(project.basePath ?: "").trimStart('/')
            "$rel:${position.line + 1}"
        } else "unknown location"

        val sessionName = session.sessionName
        val prompt = "Debugger paused in \"$sessionName\" at $posStr. Help me debug this."

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
        toolWindow.show {
            val content = toolWindow.contentManager.getContent(0) ?: return@show
            val panel = content.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(prompt)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val session = XDebuggerManager.getInstance(project).currentSession
        e.presentation.isEnabledAndVisible = session != null && session.isSuspended
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
