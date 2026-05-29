package com.khalibre.link2command.devpanel.config

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

class ConfigPanel : JPanel(BorderLayout()) {

    private val baseBranchField = JBTextField()
    private val stackRemoteCombo = JComboBox(arrayOf("origin", "upstream"))
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

    init {
        border = JBUI.Borders.empty(10, 12)
        buildUi()
        loadConfig()
    }

    private fun buildUi() {
        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
            insets = JBUI.insets(2, 0, 2, 0)
        }

        fun sectionLabel(text: String): JBLabel {
            return JBLabel(text).apply {
                font = font.deriveFont(font.size - 1f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                border = JBUI.Borders.emptyTop(8)
            }
        }

        fun fieldLabel(text: String) = JBLabel(text).apply {
            font = font.deriveFont(font.size - 1f)
            border = JBUI.Borders.emptyTop(4)
        }

        fun hint(text: String) = JBLabel(text).apply {
            font = font.deriveFont(font.size - 2f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }

        // ── Git section ──────────────────────────────────────────────────────
        gbc.gridy = 0; form.add(sectionLabel("GIT"), gbc)
        gbc.gridy = 1; form.add(fieldLabel("Base branch"), gbc)
        gbc.gridy = 2; form.add(baseBranchField, gbc)
        gbc.gridy = 3; form.add(fieldLabel("Stack remote"), gbc)
        gbc.gridy = 4; form.add(stackRemoteCombo.apply { maximumSize = Dimension(Int.MAX_VALUE, 28) }, gbc)
        gbc.gridy = 5; form.add(fieldLabel("Default reviewers"), gbc)
        gbc.gridy = 6; form.add(reviewersField, gbc)
        gbc.gridy = 7; form.add(hint("Comma-separated GitHub usernames"), gbc)
        gbc.gridy = 8; form.add(fieldLabel("User session (cookie for PR upload)"), gbc)
        gbc.gridy = 9; form.add(userSessionField, gbc)

        // ── Jira section ─────────────────────────────────────────────────────
        gbc.gridy = 10; form.add(sectionLabel("JIRA"), gbc)
        gbc.gridy = 11; form.add(fieldLabel("Base URL"), gbc)
        gbc.gridy = 12; form.add(jiraUrlField, gbc)
        gbc.gridy = 13; form.add(fieldLabel("Project key"), gbc)
        gbc.gridy = 14; form.add(projectKeyField, gbc)
        gbc.gridy = 15; form.add(fieldLabel("Email"), gbc)
        gbc.gridy = 16; form.add(emailField, gbc)
        gbc.gridy = 17; form.add(fieldLabel("API token"), gbc)
        gbc.gridy = 18; form.add(apiTokenField, gbc)
        gbc.gridy = 19; form.add(hint("Stored in ~/.config/devtools/config.json"), gbc)

        // spacer
        gbc.gridy = 20
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        form.add(JPanel(), gbc)

        val scroll = JBScrollPane(form).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)

        // ── Save button + status ─────────────────────────────────────────────
        val bottom = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
        }
        val saveBtn = JButton("Save Config").apply {
            addActionListener { saveConfig() }
        }
        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(statusLabel)
        }
        bottom.add(saveBtn, BorderLayout.NORTH)
        bottom.add(statusRow, BorderLayout.SOUTH)
        add(bottom, BorderLayout.SOUTH)
    }

    fun loadConfig() {
        val cfg = DevConfig.load()
        baseBranchField.text = cfg.git.base_branch
        val remoteIdx = (stackRemoteCombo.model as DefaultComboBoxModel<String>)
            .getIndexOf(cfg.git.stack_remote)
        if (remoteIdx >= 0) stackRemoteCombo.selectedIndex = remoteIdx
        reviewersField.text = cfg.git.default_reviewers.joinToString(", ")
        userSessionField.text = cfg.git.user_session
        jiraUrlField.text = cfg.jira.base_url
        projectKeyField.text = cfg.jira.project_key
        emailField.text = cfg.jira.email
        apiTokenField.text = cfg.jira.api_token
        statusLabel.text = ""
    }

    private fun saveConfig() {
        val reviewers = reviewersField.text
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val cfg = DevConfig(
            git = GitConfig(
                base_branch = baseBranchField.text.trim(),
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
        )
        DevConfig.save(cfg)
        statusLabel.text = "  Saved ✓"
        statusLabel.foreground = JBUI.CurrentTheme.Label.foreground()
        Timer(2500) {
            statusLabel.text = ""
        }.apply { isRepeats = false; start() }
    }
}
