package com.khalibre.tools.devpanel.config

import com.google.gson.Gson
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.khalibre.tools.devpanel.common.ProjectPaths
import com.khalibre.tools.devpanel.tickets.TicketsPanel
import java.awt.*
import java.io.File
import javax.swing.*

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

    private companion object {
        const val HOW_TO_CONFIG_URL = "https://khalibre.atlassian.net/browse/CW-37722?focusedCommentId=103347"
        const val SOURCE_CODE_URL = "https://github.com/samansum3/kdev-tools"
    }

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
        outer.border = JBUI.Borders.empty(12, 12)

        val form = JPanel(GridBagLayout())

        fun addSection(row: Int, component: JComponent) {
            form.add(component, GridBagConstraints().apply {
                gridx = 0
                gridy = row
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = JBUI.insetsBottom(12)
            })
        }

        fun sectionLabel(text: String) = JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, font.size - 1f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }

        fun inputBlock(label: String, field: JComponent): JPanel {
            val p = JPanel(BorderLayout(0, 2)).apply { isOpaque = false }
            p.add(
                JBLabel(label).apply { font = font.deriveFont(font.size - 1f) },
                BorderLayout.NORTH
            )
            p.add(field, BorderLayout.CENTER)
            return p
        }

        fun twoColumnPanel(
            leftLabel: String,
            leftField: JComponent,
            rightLabel: String,
            rightField: JComponent
        ): JPanel {
            return JPanel(GridLayout(1, 2, JBUI.scale(4), 0)).apply {
                add(inputBlock(leftLabel, leftField))
                add(inputBlock(rightLabel, rightField))
            }
        }

        fun threeColumnPanel(
            l1: String,
            c1: JComponent,
            l2: String,
            c2: JComponent,
            l3: String,
            c3: JComponent
        ): JPanel {
            return JPanel(GridLayout(1, 3, JBUI.scale(4), 0)).apply {
                add(inputBlock(l1, c1))
                add(inputBlock(l2, c2))
                add(inputBlock(l3, c3))
            }
        }

        fun oneColumnPanel(
            label: String,
            field: JComponent
        ): JPanel {
            return JPanel(GridLayout(1, 1, 0, 0)).apply {
                add(inputBlock(label, field))
            }
        }

        val jiraSection = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(sectionLabel("JIRA"), BorderLayout.NORTH)

            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)

                    add(
                        threeColumnPanel(
                            "Base URL", jiraUrlField,
                            "Project key", projectKeyField,
                            "Time project key", timeProjectKeyField
                        )
                    )

                    add(Box.createVerticalStrut(JBUI.scale(8)))

                    add(
                        twoColumnPanel(
                            "Email", emailField,
                            "API token", apiTokenField
                        )
                    )
                },
                BorderLayout.CENTER
            )
        }

        val telegramSection = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(sectionLabel("TELEGRAM"), BorderLayout.NORTH)
            add(
                twoColumnPanel(
                    "Chat id", telegramChatIdField,
                    "Bot token", telegramBotTokenField
                ),
                BorderLayout.CENTER
            )
        }

        val calendarificSection = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(sectionLabel("CALENDARIFIC"), BorderLayout.NORTH)
            add(
                oneColumnPanel(
                    "API key",
                    calendarificApiKeyField
                ),
                BorderLayout.CENTER
            )
        }

        addSection(0, jiraSection)
        addSection(1, telegramSection)
        addSection(2, calendarificSection)

        form.add(JPanel(), GridBagConstraints().apply {
            gridx = 0
            gridy = 99
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        })

        val scroll = JBScrollPane(form).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        outer.add(scroll, BorderLayout.CENTER)

        val bottom = JPanel(BorderLayout()).apply { border = JBUI.Borders.emptyTop(8) }
        val saveBtn = JButton("Save Config").apply { addActionListener { saveConfig() } }

        // Help links sit directly above the Save Config button
        val linksAndSave = JPanel(BorderLayout(0, JBUI.scale(8)))
        linksAndSave.add(buildHelpLinks(), BorderLayout.NORTH)
        linksAndSave.add(saveBtn, BorderLayout.CENTER)
        bottom.add(linksAndSave, BorderLayout.NORTH)
        bottom.add(
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(statusLabel) },
            BorderLayout.SOUTH
        )
        outer.add(bottom, BorderLayout.SOUTH)
        return outer
    }

    // ── Help links ────────────────────────────────────────────────────────────

    private fun buildHelpLinks(): JComponent {
        fun linkRow(title: String, description: String, url: String): JComponent {
            val link = ActionLink(title) { BrowserUtil.browse(url) }.apply {
                toolTipText = url
            }
            val desc = JBLabel("<html>$description</html>").apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                font = font.deriveFont(font.size - 1f)
                setAllowAutoWrapping(true)
            }
            return JPanel(BorderLayout(0, JBUI.scale(1))).apply {
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(link) }, BorderLayout.NORTH)
                add(desc, BorderLayout.CENTER)
            }
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(
                linkRow(
                    "How to config",
                    "This is the original feature definition.",
                    HOW_TO_CONFIG_URL
                )
            )
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(
                linkRow(
                    "Source code",
                    "You can fork it, add new features, or fix bugs if a later IDE version breaks a current plugin feature.",
                    SOURCE_CODE_URL
                )
            )
        }
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
