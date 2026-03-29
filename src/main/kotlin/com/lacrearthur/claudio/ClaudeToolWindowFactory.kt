package com.lacrearthur.claudio

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

private val log = Logger.getInstance("Claudio")

class ClaudeToolWindowFactory : ToolWindowFactory, DumbAware {
    override suspend fun isApplicableAsync(project: Project): Boolean = true

    // The 3 "deprecated API" warnings from Plugin Verifier are synthetic Kotlin
    // JVM bridge methods for old Java defaults deprecated in favor of plugin.xml
    // attributes + isApplicableAsync. Known false-positive (MP-3345 / MP-7604).

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        log.warn("[CLAUDE] createToolWindowContent called, project=${project.name}")
        try {
            val panel = ClaudioTabbedPanel(project, toolWindow.disposable)
            val content = ContentFactory.getInstance().createContent(panel, "", false)
            content.isCloseable = false
            toolWindow.contentManager.addContent(content)
            log.warn("[CLAUDE] content added successfully")
        } catch (e: Throwable) {
            log.error("[CLAUDE] FATAL: createToolWindowContent failed", e)
            val errorPanel = buildErrorPanel("Plugin init failed:\n${e.javaClass.simpleName}: ${e.message}\n\nCheck idea.log for full stacktrace.")
            val content = ContentFactory.getInstance().createContent(errorPanel, "Error", false)
            toolWindow.contentManager.addContent(content)
        }
    }
}
