package com.khalibre.link2command.devpanel

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class DevPanelFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = DevPanelContent(project)
        val contentFactory = ContentFactory.getInstance()
        val tab = contentFactory.createContent(content, "", false)
        toolWindow.contentManager.addContent(tab)
    }
}
