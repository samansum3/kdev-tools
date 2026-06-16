package com.khalibre.link2command.devpanel.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.pr.GitService
import com.khalibre.link2command.devpanel.pr.PrService
import com.khalibre.link2command.devpanel.tickets.TicketsPanel
import java.awt.*
import javax.swing.*

/**
 * Config tab — contains two sub-tabs:
 *   • General        (git + Jira credentials, same as before)
 *   • Ticket Config  (done-status per type, excluded statuses/types, reload actions)
 */
class ConfigPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val baseBranchCombo = JComboBox<String>()
    private val stackRemoteCombo = JComboBox<String>()
    private val reviewersField = JBTextField()
    private val userSessionField = JBPasswordField()
    private val jiraUrlField = JBTextField()
    private val projectKeyField = JBTextField()
    private val emailField = JBTextField()
    private val apiTokenField = JBPasswordField()

    private val statusLabel = JBLabel("").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        font = font.deriveFont(font.size - 1f)
    }

    // TicketConfigPanel is created lazily; it needs a reference back to TicketsPanel
    private var ticketConfigPanel: TicketConfigPanel? = null
    private var ticketsPanelRef: TicketsPanel? = null

    private val subTabs = JBTabbedPane()

    init {
        border = JBUI.Borders.empty(0)
        buildUi()
        loadConfig()
        loadUpstreamBranches()
        loadRemotes()
    }

    fun setTicketsPanel(tp: TicketsPanel) {
        ticketsPanelRef = tp
        // Inject into TicketConfigPanel if already created
        (subTabs.getComponentAt(1) as? TicketConfigPanel)?.let { /* already wired */ }
    }

    private fun buildUi() {
        // ── General sub-tab ───────────────────────────────────────────────────
        val generalPanel = buildGeneralPanel()

        // ── Ticket Config sub-tab ─────────────────────────────────────────────
        val tcp = TicketConfigPanel(
            getTicketsPanel = { ticketsPanelRef },
            getCwDir = {
                val base = project.basePath ?: return@TicketConfigPanel null
                var dir = java.io.File(base)
                while (dir.parentFile != null) {
                    if (java.io.File(dir, ".git").isDirectory)
                        return@TicketConfigPanel java.io.File(dir, ".git/cw")
                    dir = dir.parentFile
                }
                null
            }
        )
        ticketConfigPanel = tcp

        subTabs.addTab("General", generalPanel)
        subTabs.addTab("Ticket Config", tcp)

        add(subTabs, BorderLayout.CENTER)
    }

    // ── General panel ─────────────────────────────────────────────────────────

    private fun buildGeneralPanel(): JPanel {
        val outer = JPanel(BorderLayout())
        outer.border = JBUI.Borders.empty(10, 12)

        val form = JPanel(GridBagLayout())
        form.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = requestFocusInWindow().let {}
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

        // GIT
        fullRow(0, sectionLabel("GIT"))
        leftCell(1, fieldLabel("Base branch"))
        rightCell(1, fieldLabel("Stack remote"))
        leftCell(2, baseBranchCombo.apply { maximumSize = Dimension(Int.MAX_VALUE, 28) })
        com.intellij.ui.ComboboxSpeedSearch.installOn(baseBranchCombo)
        rightCell(2, stackRemoteCombo.apply { maximumSize = Dimension(Int.MAX_VALUE, 28) })
        fullRow(3, fieldLabel("Default reviewers"))
        fullRow(4, reviewersField)
        fullRow(5, fieldLabel("User session (cookie for PR upload)"))
        fullRow(6, userSessionField)

        // JIRA
        fullRow(7, sectionLabel("JIRA"))
        leftCell(8, fieldLabel("Base URL"))
        rightCell(8, fieldLabel("Project key"))
        leftCell(9, jiraUrlField)
        rightCell(9, projectKeyField)
        leftCell(10, fieldLabel("Email"))
        rightCell(10, fieldLabel("API token"))
        leftCell(11, emailField)
        rightCell(11, apiTokenField)
        fullRow(12, hint("Stored in ~/.config/devtools/config.json"))

        // Spacer
        form.add(JPanel(), GridBagConstraints().apply {
            gridx = 0; gridy = 13; gridwidth = 3
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

    // ── Branch / remote loading ───────────────────────────────────────────────

    private fun loadRemotes() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val remotes = try {
                val result = PrService.runCmd(listOf("git", "remote"))
                if (result.exitCode != 0) listOf("origin", "upstream")
                else result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
            } catch (_: Exception) {
                listOf("origin", "upstream")
            }
            SwingUtilities.invokeLater {
                val current = stackRemoteCombo.selectedItem?.toString()
                stackRemoteCombo.removeAllItems()
                remotes.forEach { stackRemoteCombo.addItem(it) }
                val cfg = DevConfig.load()
                val preferred = current?.takeIf { remotes.contains(it) }
                    ?: cfg.git.stack_remote.takeIf { remotes.contains(it) }
                val idx = preferred?.let { p ->
                    (0 until stackRemoteCombo.itemCount).firstOrNull { stackRemoteCombo.getItemAt(it) == p }
                }
                stackRemoteCombo.selectedIndex = idx ?: 0
            }
        }
    }

    private fun loadUpstreamBranches() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val branches = GitService.fetchUpstreamBranchNames(project)
            SwingUtilities.invokeLater {
                val current = baseBranchCombo.selectedItem?.toString()
                baseBranchCombo.removeAllItems()
                baseBranchCombo.addItem("")
                branches.forEach { baseBranchCombo.addItem(it) }
                val cfg = DevConfig.load()
                val preferred = current?.takeIf { it.isNotBlank() && branches.contains(it) }
                    ?: cfg.git.base_branch.takeIf { branches.contains(it) }
                val idx = preferred?.let { p ->
                    (0 until baseBranchCombo.itemCount).firstOrNull { baseBranchCombo.getItemAt(it) == p }
                }
                baseBranchCombo.selectedIndex = idx ?: 0
            }
        }
    }

    // ── Load / Save ───────────────────────────────────────────────────────────

    fun loadConfig() {
        val cfg = DevConfig.load()
        val remIdx = (stackRemoteCombo.model as DefaultComboBoxModel<String>)
            .getIndexOf(cfg.git.stack_remote)
        if (remIdx >= 0) stackRemoteCombo.selectedIndex = remIdx
        reviewersField.text = cfg.git.default_reviewers.joinToString(", ")
        userSessionField.text = cfg.git.user_session
        jiraUrlField.text = cfg.jira.base_url
        projectKeyField.text = cfg.jira.project_key
        emailField.text = cfg.jira.email
        apiTokenField.text = cfg.jira.api_token
        statusLabel.text = ""
    }

    private fun saveConfig() {
        val reviewers = reviewersField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val existing = DevConfig.load()
        val cfg = existing.copy(
            git = GitConfig(
                base_branch = (baseBranchCombo.selectedItem as? String ?: "").trim(),
                stack_remote = stackRemoteCombo.selectedItem as String,
                default_reviewers = reviewers,
                user_session = String(userSessionField.password)
            ),
            jira = JiraConfig(
                base_url = jiraUrlField.text.trim().trimEnd('/'),
                project_key = projectKeyField.text.trim().uppercase(),
                email = emailField.text.trim(),
                api_token = String(apiTokenField.password)
            )
            // ticket config preserved as-is
        )
        DevConfig.save(cfg)
        statusLabel.text = "  Saved ✓"
        statusLabel.foreground = JBUI.CurrentTheme.Label.foreground()
        Timer(2500) { statusLabel.text = "" }.apply { isRepeats = false; start() }
    }
}
