package com.khalibre.tools.devpanel

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import com.khalibre.tools.devpanel.common.ProjectPaths
import com.khalibre.tools.devpanel.config.ConfigPanel
import com.khalibre.tools.devpanel.pr.PrTabsPanel
import com.khalibre.tools.devpanel.tickets.TicketTabsPanel
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel
import javax.swing.SwingUtilities

class DevPanelContent(private val project: Project) : JPanel(BorderLayout()) {

    private val prTabsPanel = PrTabsPanel(project)
    private val ticketTabsPanel = TicketTabsPanel(project)
    private val configPanel =
        ConfigPanel(project).also { it.setTicketsPanel { ticketTabsPanel.activeTicketsPanel() } }

    private val tabs = JBTabbedPane().apply {
        addTab("PR Tools", prTabsPanel)
        addTab("Tickets", ticketTabsPanel)
        addTab("Config", configPanel)
    }

    init {
        add(tabs, BorderLayout.CENTER)
        tabs.selectedIndex = loadSelectedMainTab().coerceIn(0, tabs.tabCount - 1)
        tabs.addChangeListener {
            saveSelectedMainTab(tabs.selectedIndex)
            refreshCurrentTab()
        }
        SwingUtilities.invokeLater { refreshCurrentTab() }
    }

    fun refreshCurrentTab() {
        when (tabs.selectedIndex) {
            0 -> prTabsPanel.refreshActive()
            1 -> ticketTabsPanel.refreshActive()
            2 -> configPanel.loadConfig()
        }
    }

    // ── Remember which main tab (PR Tools / Tickets / Config) was last open ────

    private fun stateFile(): File? = ProjectPaths.cwDir(project)?.let { File(it, "panel-state.json") }

    private fun loadSelectedMainTab(): Int {
        val f = stateFile() ?: return 0
        if (!f.exists()) return 0
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = Gson().fromJson(f.readText(), Map::class.java) as Map<String, Double>
            (map["selectedMainTab"] ?: 0.0).toInt()
        } catch (_: Exception) {
            0
        }
    }

    private fun saveSelectedMainTab(index: Int) {
        val f = stateFile() ?: return
        try {
            f.parentFile?.mkdirs()
            f.writeText(Gson().toJson(mapOf("selectedMainTab" to index)))
        } catch (_: Exception) {
        }
    }
}
