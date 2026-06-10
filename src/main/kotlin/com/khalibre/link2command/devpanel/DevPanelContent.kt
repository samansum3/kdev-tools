package com.khalibre.link2command.devpanel

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import com.khalibre.link2command.devpanel.config.ConfigPanel
import com.khalibre.link2command.devpanel.pr.PrPanel
import com.khalibre.link2command.devpanel.tickets.TicketsPanel
import java.awt.BorderLayout
import javax.swing.JPanel

class DevPanelContent(project: Project) : JPanel(BorderLayout()) {

    private val prPanel = PrPanel(project)
    private val ticketsPanel = TicketsPanel()
    private val configPanel = ConfigPanel(project)

    private val tabs = JBTabbedPane().apply {
        addTab("PR Tools", prPanel)
        addTab("Tickets", ticketsPanel)
        addTab("Config", configPanel)
    }

    init {
        add(tabs, BorderLayout.CENTER)
        tabs.addChangeListener { refreshCurrentTab() }
    }

    fun refreshCurrentTab() {
        when (tabs.selectedIndex) {
            0 -> prPanel.refresh()
            1 -> ticketsPanel.refresh()
            2 -> configPanel.loadConfig()
        }
    }
}
