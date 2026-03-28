package com.lacrearthur.claudio

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Processor
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * Shift+Shift integration: shows "Ask Claude: <query>" in Search Everywhere.
 * Selecting it populates the Claudio input bar with the typed query.
 */
class ClaudioSearchEverywhereContributor(
    private val project: Project
) : SearchEverywhereContributor<String> {

    private val icon by lazy {
        try {
            IconLoader.getIcon("/icons/claudio-icon.svg", ClaudioSearchEverywhereContributor::class.java)
        } catch (_: Exception) {
            null
        }
    }

    override fun getSearchProviderId(): String = "ClaudioSearchProvider"
    override fun getGroupName(): String = "Claude"
    override fun getSortWeight(): Int = 200
    override fun showInFindResults(): Boolean = false
    override fun isEmptyPatternSupported(): Boolean = false

    override fun fetchElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in String>
    ) {
        if (pattern.isNotBlank()) {
            consumer.process("Ask Claude: $pattern")
        }
    }

    override fun processSelectedItem(selected: String, modifiers: Int, searchText: String): Boolean {
        val query = searchText.trim()
        if (query.isBlank()) return false
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude") ?: return false
        tw.show {
            val panel = tw.contentManager.getContent(0)?.component as? ClaudioTabbedPanel ?: return@show
            panel.setInputText(query)
        }
        return true
    }

    override fun getElementsRenderer(): ListCellRenderer<in String> {
        return object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = value as? String ?: ""
                icon = this@ClaudioSearchEverywhereContributor.icon
                return c
            }
        }
    }

    override fun getDataForItem(element: String, dataId: String): Any? = null
}

class ClaudioSearchEverywhereContributorFactory : SearchEverywhereContributorFactory<String> {
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<String> {
        return ClaudioSearchEverywhereContributor(initEvent.project!!)
    }
}
