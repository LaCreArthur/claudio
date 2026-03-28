package com.lacrearthur.claudio

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Injects the last captured run configuration output (regardless of exit code) into the
 * Claudio input bar. Complements the failure-only balloon from RunFailureListener by providing
 * on-demand access for successful or any-state runs.
 *
 * Output is captured by the existing RunFailureListener ProcessAdapter; no separate listener needed.
 */
class SendLastRunOutputAction : AnAction("Send Last Run Output to Claude") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val snapshot = RunFailureListener.getLastRun(project)
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return

        if (snapshot == null) {
            tw.show {
                val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel
                    ?: return@show
                panel.setInputText(
                    "No run output has been captured yet. Run a run configuration first, then use this action."
                )
            }
            return
        }

        val prompt = buildPrompt(snapshot)
        tw.show {
            val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel
                ?: return@show
            panel.setInputText(prompt)
        }
    }

    private fun buildPrompt(snapshot: RunFailureListener.Companion.LastRunSnapshot): String {
        val sb = StringBuilder()
        val status = if (snapshot.exitCode == 0) "succeeded (exit code 0)" else "failed (exit code ${snapshot.exitCode})"
        sb.append("Last run output from '${snapshot.configName}' - $status:\n\n")
        if (snapshot.output.isNotBlank()) {
            sb.append("```\n")
            sb.append(snapshot.output)
            sb.append("\n```\n")
        } else {
            sb.append("(no output captured)\n")
        }
        sb.append("\nWhat can you tell me about this output?")
        return sb.toString()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
