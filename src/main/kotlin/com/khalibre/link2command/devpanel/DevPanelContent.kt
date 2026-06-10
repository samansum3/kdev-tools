package com.khalibre.link2command.devpanel

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.config.ConfigPanel
import com.khalibre.link2command.devpanel.pr.PrPanel
import com.khalibre.link2command.devpanel.tickets.TicketsPanel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
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
        // ── Header row: title + refresh button ────────────────────────────
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 10, 4, 8)
        }

        val refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            toolTipText = "Refresh current tab"
            addActionListener { refreshCurrentTab() }
        }

        val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(refreshBtn)
        }
        header.add(btnRow, BorderLayout.EAST)

        add(header, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        // Auto-load PR tab on first open
        tabs.addChangeListener {
            refreshCurrentTab()
        }
    }

    fun refreshCurrentTab() {
        when (tabs.selectedIndex) {
            0 -> prPanel.refresh()
            1 -> ticketsPanel.refresh()
            2 -> configPanel.loadConfig()
        }
    }
}
