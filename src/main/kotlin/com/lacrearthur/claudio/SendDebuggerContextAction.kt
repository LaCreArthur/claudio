package com.lacrearthur.claudio

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import javax.swing.Icon
import javax.swing.SwingUtilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * When the debugger is paused at a breakpoint, injects the current pause location and
 * visible local variables into the Claudio input bar.
 * Shown only when a debug session is suspended.
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

        // Collect variables off EDT to avoid blocking the UI
        ApplicationManager.getApplication().executeOnPooledThread {
            val varStr = frame?.let { collectVariables(it) } ?: ""
            val prompt = "Debugger paused in \"$sessionName\" at $posStr. Help me debug this.$varStr"

            SwingUtilities.invokeLater {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return@invokeLater
                toolWindow.show {
                    val content = toolWindow.contentManager.getContent(0) ?: return@show
                    val panel = content.component as? ClaudioTabbedPanel ?: return@show
                    panel.setInputText(prompt)
                }
            }
        }
    }

    /**
     * Reads up to 10 top-level variables from the frame's variable tree.
     * Runs on a pooled thread. Times out at 5s total, 500ms per variable.
     */
    private fun collectVariables(frame: XStackFrame): String {
        val latch = CountDownLatch(1)
        val lines = mutableListOf<String>()

        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                val limit = minOf(children.size(), 10)
                for (i in 0 until limit) {
                    val name = children.getName(i)
                    val xValue = children.getValue(i)
                    val valLatch = CountDownLatch(1)
                    val sb = StringBuilder()

                    xValue.computePresentation(object : XValueNode {
                        override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                            sb.append(value)
                            valLatch.countDown()
                        }
                        override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                            presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                                override fun renderValue(value: String) { sb.append(value) }
                                override fun renderValue(value: String, key: TextAttributesKey) { sb.append(value) }
                                override fun renderStringValue(value: String) { sb.append("\"$value\"") }
                                override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) { sb.append("\"$value\"") }
                                override fun renderNumericValue(value: String) { sb.append(value) }
                                override fun renderKeywordValue(value: String) { sb.append(value) }
                                override fun renderComment(comment: String) {}
                                override fun renderSpecialSymbol(symbol: String) { sb.append(symbol) }
                                override fun renderError(error: String) { sb.append("<$error>") }
                            })
                            valLatch.countDown()
                        }
                        override fun setFullValueEvaluator(evaluator: XFullValueEvaluator) {}
                        override fun isObsolete(): Boolean = false
                    }, XValuePlace.TOOLTIP)

                    valLatch.await(500, TimeUnit.MILLISECONDS)
                    if (sb.isNotEmpty()) lines.add("  $name = $sb")
                }
                if (last) latch.countDown()
            }
            override fun tooManyChildren(remaining: Int) { latch.countDown() }
            override fun setAlreadySorted(alreadySorted: Boolean) {}
            override fun setErrorMessage(errorMessage: String) { latch.countDown() }
            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { latch.countDown() }
            override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
            override fun isObsolete(): Boolean = false
        })

        latch.await(5, TimeUnit.SECONDS)
        return if (lines.isEmpty()) "" else "\n\nVariables:\n${lines.joinToString("\n")}"
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) { e.presentation.isEnabledAndVisible = false; return }
        val session = XDebuggerManager.getInstance(project).currentSession
        e.presentation.isEnabledAndVisible = session != null && session.isSuspended
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
