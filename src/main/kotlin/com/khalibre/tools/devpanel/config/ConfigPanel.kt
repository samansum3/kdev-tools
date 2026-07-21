package com.khalibre.tools.devpanel.config

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.khalibre.tools.devpanel.common.ProjectPaths
import com.khalibre.tools.devpanel.tickets.TicketsPanel
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Config tab — contains three sub-tabs:
 *   • General        (Jira / Telegram / Calendarific credentials)
 *   • PR Config      (base branch, default reviewers, user session, merge permission)
 *   • Ticket Config  (done-status per type, excluded statuses/types, reload actions)
 */
class ConfigPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val jiraUrlField = JBTextField()
    private val projectKeyField = JBTextField()
    private val timeProjectKeyField = JBTextField()
    private val emailField = JBTextField()
    private val apiTokenField = JBPasswordField()
    private val telegramChatIdField = JBTextField()
    private val telegramBotTokenField = JBPasswordField()
    private val calendarificApiKeyField = JBPasswordField()

    private val statusLabel = JBLabel("").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        font = font.deriveFont(font.size - 1f)
    }

    // TicketConfigPanel is created lazily; it needs a reference back to the active TicketsPanel
    private var ticketConfigPanel: TicketConfigPanel? = null
    private var prConfigPanel: PrConfigPanel? = null
    private var ticketsPanelSupplier: (() -> TicketsPanel?)? = null

    private val subTabs = JBTabbedPane()

    private fun cwDir() = ProjectPaths.cwDir(project)

    init {
        border = JBUI.Borders.empty(0)
        buildUi()
        loadConfig()
    }

    /** [supplier] should return whichever TicketsPanel sub-tab is currently active. */
    fun setTicketsPanel(supplier: () -> TicketsPanel?) {
        ticketsPanelSupplier = supplier
    }

    private fun buildUi() {
        // ── General sub-tab ───────────────────────────────────────────────────
        val generalPanel = buildGeneralPanel()

        // ── PR Config sub-tab ────────────────────────────────────────────────
        val pcp = PrConfigPanel(project, getCwDir = { cwDir() })
        prConfigPanel = pcp

        // ── Ticket Config sub-tab ─────────────────────────────────────────────
        val tcp = TicketConfigPanel(
            getTicketsPanel = { ticketsPanelSupplier?.invoke() },
            getCwDir = { cwDir() }
        )
        ticketConfigPanel = tcp

        subTabs.addTab("General", generalPanel)
        subTabs.addTab("PR Config", pcp)
        subTabs.addTab("Ticket Config", tcp)

        restoreSelectedSubTab()
        subTabs.addChangeListener { saveSelectedSubTab() }

        add(subTabs, BorderLayout.CENTER)
    }

    // ── Selected sub-tab persistence ────────────────────────────────────────────

    private fun subTabStateFile(): File? = cwDir()?.let { File(it, "config-ui-state.json") }

    private fun restoreSelectedSubTab() {
        val f = subTabStateFile() ?: return
        if (!f.exists()) return
        try {
            @Suppress("UNCHECKED_CAST")
            val map = Gson().fromJson(f.readText(), Map::class.java) as? Map<String, Any> ?: return
            val index = (map["selectedSubTab"] as? Double)?.toInt() ?: return
            if (index in 0 until subTabs.tabCount) subTabs.selectedIndex = index
        } catch (_: Exception) {
        }
    }

    private fun saveSelectedSubTab() {
        val f = subTabStateFile() ?: return
        try {
            f.parentFile?.mkdirs()
            f.writeText(Gson().toJson(mapOf("selectedSubTab" to subTabs.selectedIndex)))
        } catch (_: Exception) {
        }
    }

    // ── General panel ─────────────────────────────────────────────────────────

    private fun buildGeneralPanel(): JPanel {
        val outer = JPanel(BorderLayout())
        outer.border = JBUI.Borders.empty(10, 12)

        val form = JPanel(GridBagLayout())
        form.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = requestFocusInWindow().let {}
        })

        fun sectionLabel(text: String) = JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, font.size - 1f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border = JBUI.Borders.emptyTop(12)
        }

        fun fieldLabel(text: String) = JBLabel(text).apply {
            font = font.deriveFont(font.size - 1f)
            border = JBUI.Borders.emptyTop(6)
        }

        fun hint(text: String) = JBLabel(text).apply {
            font = font.deriveFont(font.size - 2f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }

        fun fullRow(row: Int, comp: JComponent) {
            form.add(comp, GridBagConstraints().apply {
                gridx = 0; gridy = row; gridwidth = 3
                fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
                insets = JBUI.insets(0)
            })
        }

        fun leftCell(row: Int, comp: JComponent) {
            form.add(comp, GridBagConstraints().apply {
                gridx = 0; gridy = row; gridwidth = 1
                fill = GridBagConstraints.HORIZONTAL; weightx = 0.5
                insets = JBUI.insets(0, 0, 0, 4)
            })
        }

        fun rightCell(row: Int, comp: JComponent) {
            form.add(comp, GridBagConstraints().apply {
                gridx = 1; gridy = row; gridwidth = 1
                fill = GridBagConstraints.HORIZONTAL; weightx = 0.5
                insets = JBUI.insets(0, 4, 0, 0)
            })
        }

        // Three-way row variant (used for Base URL / Project key / Time project key)
        fun cell3(row: Int, col: Int, comp: JComponent) {
            val (left, right) = when (col) {
                0 -> 0 to 4
                1 -> 4 to 4
                else -> 4 to 0
            }
            form.add(comp, GridBagConstraints().apply {
                gridx = col; gridy = row; gridwidth = 1
                fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 / 3
                insets = JBUI.insets(0, left, 0, right)
            })
        }

        // JIRA
        fullRow(0, sectionLabel("JIRA"))
        cell3(1, 0, fieldLabel("Base URL"))
        cell3(1, 1, fieldLabel("Project key"))
        cell3(1, 2, fieldLabel("Time project key"))
        cell3(2, 0, jiraUrlField)
        cell3(2, 1, projectKeyField)
        cell3(2, 2, timeProjectKeyField)
        leftCell(3, fieldLabel("Email"))
        rightCell(3, fieldLabel("API token"))
        leftCell(4, emailField)
        rightCell(4, apiTokenField)

        // TELEGRAM
        fullRow(5, sectionLabel("TELEGRAM"))
        leftCell(6, fieldLabel("Chat id"))
        rightCell(6, fieldLabel("Bot token"))
        leftCell(7, telegramChatIdField)
        rightCell(7, telegramBotTokenField)

        // CALENDARIFIC
        fullRow(8, sectionLabel("CALENDARIFIC"))
        fullRow(9, fieldLabel("API key"))
        fullRow(10, calendarificApiKeyField)

        // Spacer
        form.add(JPanel(), GridBagConstraints().apply {
            gridx = 0; gridy = 14; gridwidth = 3
            weighty = 1.0; fill = GridBagConstraints.BOTH
        })

        val scroll = JBScrollPane(form).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        outer.add(scroll, BorderLayout.CENTER)

        val bottom = JPanel(BorderLayout()).apply { border = JBUI.Borders.emptyTop(8) }
        val saveBtn = JButton("Save Config").apply { addActionListener { saveConfig() } }
        bottom.add(saveBtn, BorderLayout.NORTH)
        bottom.add(
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(statusLabel) },
            BorderLayout.SOUTH
        )
        outer.add(bottom, BorderLayout.SOUTH)
        return outer
    }

    // ── Load / Save ───────────────────────────────────────────────────────────

    fun loadConfig() {
        val cfg = DevConfig.load(cwDir())
        jiraUrlField.text = cfg.jira.base_url
        projectKeyField.text = cfg.jira.project_key
        timeProjectKeyField.text = cfg.jira.time_project_key
        emailField.text = cfg.jira.email
        apiTokenField.text = cfg.jira.api_token
        statusLabel.text = ""
        telegramChatIdField.text = cfg.telegram.chat_id
        telegramBotTokenField.text = cfg.telegram.bot_token
        calendarificApiKeyField.text = cfg.calendarific.api_key

        prConfigPanel?.loadConfig()
    }

    private fun saveConfig() {
        val cw = cwDir()
        val existing = DevConfig.load(cw)
        val cfg = existing.copy(
            jira = JiraConfig(
                base_url = jiraUrlField.text.trim().trimEnd('/'),
                project_key = projectKeyField.text.trim().uppercase(),
                time_project_key = timeProjectKeyField.text.trim().uppercase(),
                email = emailField.text.trim(),
                api_token = String(apiTokenField.password)
            ),
            telegram = TelegramConfig(
                chat_id = telegramChatIdField.text.trim(),
                bot_token = String(telegramBotTokenField.password)
            ),
            calendarific = CalendarificConfig(
                api_key = String(calendarificApiKeyField.password)
            )
        )
        DevConfig.save(cfg, cw)
        statusLabel.text = "  Saved ✓"
        statusLabel.foreground = JBUI.CurrentTheme.Label.foreground()
        Timer(2500) { statusLabel.text = "" }.apply { isRepeats = false; start() }
    }
}
