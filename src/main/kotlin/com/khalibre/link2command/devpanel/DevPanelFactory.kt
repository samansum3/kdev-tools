package com.khalibre.link2command.devpanel

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class DevPanelFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = DevPanelContent(project)
        val tab = ContentFactory.getInstance().createContent(content, "", false)
        toolWindow.contentManager.addContent(tab)

        // Refresh button in the tool window title bar
        toolWindow.setTitleActions(listOf(object :
            AnAction("Refresh", "Refresh current tab", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                content.refreshCurrentTab()
            }
        }))
    }
}
