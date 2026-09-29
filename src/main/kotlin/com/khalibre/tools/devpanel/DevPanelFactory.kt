package com.khalibre.tools.devpanel

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.khalibre.tools.devpanel.standup.StandupPreviewDialog

class DevPanelFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = DevPanelContent(project)
        val tab = ContentFactory.getInstance().createContent(content, "", false)
        toolWindow.contentManager.addContent(tab)

        // Preview + send standup report to Telegram
        toolWindow.setTitleActions(listOf(object :
            AnAction(
                "Send Standup",
                "Preview and send standup report to Telegram",
                AllIcons.Actions.Show
            ) {
            override fun actionPerformed(e: AnActionEvent) {
                StandupPreviewDialog(project).show()
            }
        }))
    }
}
