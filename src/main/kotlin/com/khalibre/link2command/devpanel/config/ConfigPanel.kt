package com.khalibre.link2command.devpanel.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.pr.GitService
import com.khalibre.link2command.devpanel.pr.PrService
import com.khalibre.link2command.devpanel.tickets.TicketsPanel
import com.khalibre.link2command.devpanel.tickets.WrapLayout
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

    // TicketConfigPanel is created lazily; it needs a reference back to the active TicketsPanel
    private var ticketConfigPanel: TicketConfigPanel? = null
    private var ticketsPanelSupplier: (() -> TicketsPanel?)? = null

    private val hasMergePermission = makeConfigBadge("Yes", true)
    private val noMergePermission = makeConfigBadge("No", false)

    private val subTabs = JBTabbedPane()

    init {
        border = JBUI.Borders.empty(0)
        buildUi()
        loadConfig()
        loadUpstreamBranches()
        loadRemotes()
        wireMergePermissionExclusivity()
    }


    /** Keeps exactly one of Compact/Default active — clicking one turns the other off. */
    private fun wireMergePermissionExclusivity() {
        fun select(chosen: JLabel, other: JLabel) {
            chosen.putClientProperty("active", true)
            other.putClientProperty("active", false)
            TicketsPanel.applyBadgeStyle(chosen)
            TicketsPanel.applyBadgeStyle(other)
            chosen.repaint(); other.repaint()
        }
        hasMergePermission.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = select(hasMergePermission, noMergePermission)
        })
        noMergePermission.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = select(noMergePermission, hasMergePermission)
        })
    }

    private fun makeConfigBadge(text: String, initiallyActive: Boolean): JLabel {
        val label = JLabel(text)
        label.font = label.font.deriveFont(label.font.size - 2f)
        label.isOpaque = true
        label.putClientProperty("active", initiallyActive)
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        TicketsPanel.applyBadgeStyle(label)
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                label.putClientProperty("active", label.getClientProperty("active") != true)
                TicketsPanel.applyBadgeStyle(label)
                label.repaint()
                // Note: don't call refresh/save — user clicks Save explicitly
            }

            override fun mouseEntered(e: MouseEvent) {
                label.putClientProperty("hovered", true)
                TicketsPanel.applyBadgeStyle(label)
            }

            override fun mouseExited(e: MouseEvent) {
                label.putClientProperty("hovered", false)
                TicketsPanel.applyBadgeStyle(label)
            }
        })
        return label
    }

    /** [supplier] should return whichever TicketsPanel sub-tab is currently active. */
    fun setTicketsPanel(supplier: () -> TicketsPanel?) {
        ticketsPanelSupplier = supplier
    }

    private fun buildUi() {
        // ── General sub-tab ───────────────────────────────────────────────────
        val generalPanel = buildGeneralPanel()

        // ── Ticket Config sub-tab ─────────────────────────────────────────────
        val tcp = TicketConfigPanel(
            getTicketsPanel = { ticketsPanelSupplier?.invoke() },
            getCwDir = { com.khalibre.link2command.devpanel.common.ProjectPaths.cwDir(project) }
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

        val itemModeRow = JPanel(WrapLayout(FlowLayout.LEFT, 4, 3)).apply {
            isOpaque = false; alignmentX = LEFT_ALIGNMENT
        }
        itemModeRow.add(noMergePermission)
        itemModeRow.add(hasMergePermission)
        leftCell(7, fieldLabel("Has merge permission"))
        leftCell(8, itemModeRow)

        // JIRA
        fullRow(9, sectionLabel("JIRA"))
        leftCell(10, fieldLabel("Base URL"))
        rightCell(10, fieldLabel("Project key"))
        leftCell(11, jiraUrlField)
        rightCell(11, projectKeyField)
        leftCell(12, fieldLabel("Email"))
        rightCell(12, fieldLabel("API token"))
        leftCell(13, emailField)
        rightCell(13, apiTokenField)
        fullRow(14, hint("Stored in ~/.config/devtools/config.json"))

        // Spacer
        form.add(JPanel(), GridBagConstraints().apply {
            gridx = 0; gridy = 15; gridwidth = 3
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

        hasMergePermission.putClientProperty("active", cfg.git.can_merge)
        noMergePermission.putClientProperty("active", !cfg.git.can_merge)
        TicketsPanel.applyBadgeStyle(hasMergePermission)
        TicketsPanel.applyBadgeStyle(noMergePermission)
    }

    private fun saveConfig() {
        val reviewers = reviewersField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val existing = DevConfig.load()
        val cfg = existing.copy(
            git = GitConfig(
                base_branch = (baseBranchCombo.selectedItem as? String ?: "").trim(),
                stack_remote = stackRemoteCombo.selectedItem as String,
                default_reviewers = reviewers,
                user_session = String(userSessionField.password),
                can_merge = hasMergePermission.getClientProperty("active") == true
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
