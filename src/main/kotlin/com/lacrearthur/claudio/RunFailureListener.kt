package com.lacrearthur.claudio

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Fires a Claudio balloon notification when a run configuration exits with a non-zero code.
 * The balloon has a "Send to Claude" action that injects the failure output into the input bar.
 */
class RunFailureListener(private val project: Project) : ExecutionListener {

    private val outputBuffers = ConcurrentHashMap<ProcessHandler, StringBuilder>()

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val sb = StringBuilder()
        outputBuffers[handler] = sb
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.STDOUT || outputType == ProcessOutputTypes.STDERR) {
                    sb.append(event.text)
                }
            }
        })
    }

    override fun processTerminated(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        exitCode: Int
    ) {
        val output = outputBuffers.remove(handler)?.toString() ?: ""
        if (exitCode == 0) return

        val configName = env.runProfile?.name ?: "Run"
        val prompt = buildPrompt(configName, exitCode, output)

        val notification = Notification(
            "Claudio",
            "Run failed: $configName",
            "Exit code $exitCode - click Send to inject into Claude",
            NotificationType.WARNING
        )
        notification.addAction(object : AnAction("Send to Claude") {
            override fun actionPerformed(e: AnActionEvent) {
                val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return
                tw.show {
                    val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
                    panel.setInputText(prompt)
                }
            }
        })
        Notifications.Bus.notify(notification, project)
    }

    private fun buildPrompt(configName: String, exitCode: Int, output: String): String {
        val sb = StringBuilder("Run configuration '$configName' failed with exit code $exitCode.\n")
        if (output.isNotBlank()) {
            sb.append("\nOutput:\n```\n")
            sb.append(output.takeLast(3000))
            sb.append("\n```\n")
        }
        sb.append("\nWhat went wrong and how should I fix it?")
        return sb.toString()
    }
}
