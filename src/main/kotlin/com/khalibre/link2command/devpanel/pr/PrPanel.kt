package com.khalibre.link2command.devpanel.pr

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.config.DevConfig
import java.awt.*
import javax.swing.*
import javax.swing.border.CompoundBorder

class PrPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val baseBranchCombo = JComboBox<String>()
    private val cardsPanel = JPanel()
    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }
    private var upstreamRepo: String? = null
    private var ghUser: String? = null

    // Refresh/sync icon button
    private val syncButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Fetch upstream branches (git fetch upstream --prune)"
        isFocusPainted = false
        isBorderPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(24, 24)
        minimumSize = Dimension(24, 24)
        maximumSize = Dimension(24, 24)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    init {
        border = JBUI.Borders.empty(8, 10)
        buildUi()
        loadUpstreamBranches()
    }

    private fun buildUi() {
        // ── Top toolbar ────────────────────────────────────────────────────
        val toolbar = JPanel(BorderLayout(6, 0)).apply {
            border = JBUI.Borders.emptyBottom(8)
        }

        val branchLabel = JBLabel("Base:").apply {
            font = font.deriveFont(font.size - 1f)
        }

        // Combo: auto-sized to content, not filling
        baseBranchCombo.apply {
            addItem("— none —")
            addActionListener { refresh() }
            preferredSize = Dimension(200, 28)
            maximumSize = Dimension(200, 28)
            minimumSize = Dimension(80, 28)
        }

        syncButton.addActionListener { fetchUpstreamAndReload() }

        val branchRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(branchLabel)
            add(Box.createHorizontalStrut(4))
            add(baseBranchCombo)
            add(Box.createHorizontalStrut(4))
            add(syncButton)
        }

        toolbar.add(branchRow, BorderLayout.CENTER)
        toolbar.add(statusLabel, BorderLayout.SOUTH)

        add(toolbar, BorderLayout.NORTH)

        // ── Cards list ─────────────────────────────────────────────────────
        cardsPanel.layout = BoxLayout(cardsPanel, BoxLayout.Y_AXIS)
        cardsPanel.border = JBUI.Borders.empty()

        val scroll = JBScrollPane(cardsPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)
    }

    /** Load upstream branches from `git branch -r | grep '^  upstream/'` */
    fun loadUpstreamBranches(callback: (() -> Unit)? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val branches = fetchUpstreamBranchNames()
            SwingUtilities.invokeLater {
                populateBranchCombo(branches)
                callback?.invoke()
            }
        }
    }

    private fun fetchUpstreamBranchNames(): List<String> {
        return try {
            val workDir = project.basePath?.let { java.io.File(it) }
            val result = PrService.runCmd(
                listOf("bash", "-c", "git branch -r | grep '^  upstream/'"),
                workDir
            )
            if (result.exitCode != 0) return emptyList()
            result.stdout.lines()
                .map { it.trim() }
                .filter { it.startsWith("upstream/") }
                .map { it.removePrefix("upstream/") }
                .filter { it.isNotBlank() && !it.contains("->") }
                .sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun populateBranchCombo(branches: List<String>) {
        val previousSelection = baseBranchCombo.selectedItem?.toString()
        baseBranchCombo.removeAllItems()
        baseBranchCombo.addItem("— none —")
        branches.forEach { baseBranchCombo.addItem(it) }

        // Try to restore previous selection, else fall back to config default
        val cfg = DevConfig.load()
        val preferred = previousSelection?.takeIf { it != "— none —" && branches.contains(it) }
            ?: cfg.git.base_branch.takeIf { branches.contains(it) }
        val idx = if (preferred != null) (0 until baseBranchCombo.itemCount)
            .firstOrNull { baseBranchCombo.getItemAt(it) == preferred } else null
        baseBranchCombo.selectedIndex = idx ?: 0
        baseBranchCombo.revalidate()
    }

    /** git fetch upstream --prune, then reload branch list */
    private fun fetchUpstreamAndReload() {
        setSyncSpinning(true)
        setStatus("Fetching upstream…")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val workDir = project.basePath?.let { java.io.File(it) }
                PrService.runCmd(listOf("git", "fetch", "upstream", "--prune"), workDir)
                val branches = fetchUpstreamBranchNames()
                SwingUtilities.invokeLater {
                    populateBranchCombo(branches)
                    setSyncSpinning(false)
                    setStatus("")
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setSyncSpinning(false)
                    setStatus("Fetch failed: ${e.message?.take(60)}")
                }
            }
        }
    }

    private var spinTimer: Timer? = null
    private val spinIcons = listOf(
        AllIcons.Actions.Refresh,
        AllIcons.Process.Step_1,
        AllIcons.Process.Step_2,
        AllIcons.Process.Step_3,
        AllIcons.Process.Step_4
    )

    private fun setSyncSpinning(spinning: Boolean) {
        spinTimer?.stop()
        spinTimer = null
        if (spinning) {
            var frame = 0
            spinTimer = Timer(120) {
                syncButton.icon = spinIcons[frame % spinIcons.size]
                frame++
            }.also { it.start() }
            syncButton.isEnabled = false
        } else {
            syncButton.icon = AllIcons.Actions.Refresh
            syncButton.isEnabled = true
        }
    }

    fun refresh() {
        setStatus("Loading…")
        cardsPanel.removeAll()
        cardsPanel.revalidate()
        cardsPanel.repaint()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (upstreamRepo == null) upstreamRepo = PrService.upstreamRepo(project)
                if (ghUser == null) ghUser = PrService.currentGhUser()

                val repo = upstreamRepo
                if (repo == null) {
                    SwingUtilities.invokeLater {
                        setStatus("No upstream remote found")
                        showError("Could not determine upstream repo.\nMake sure you have an 'upstream' git remote.")
                    }
                    return@executeOnPooledThread
                }

                val selectedBranch = baseBranchCombo.selectedItem?.toString()
                    ?.takeIf { it != "— none —" }

                val prs = PrService.fetchPrs(repo, selectedBranch)

                val mergeableMap = PrService.fetchMergeableStates(repo, prs.map { it.number })
                prs.forEach { it.mergeable = mergeableMap[it.number] ?: MergeableState.UNKNOWN }

                SwingUtilities.invokeLater {
                    cardsPanel.removeAll()
                    if (prs.isEmpty()) {
                        val empty = JBLabel("No open PRs found").apply {
                            border = JBUI.Borders.empty(16, 4)
                            foreground = JBUI.CurrentTheme.Label.disabledForeground()
                        }
                        cardsPanel.add(empty)
                    } else {
                        val awaitingCount = prs.count { it.reviewState == ReviewState.AWAITING }
                        val summaryLabel = JBLabel(
                            buildSummaryText(
                                selectedBranch,
                                prs.size,
                                awaitingCount
                            )
                        ).apply {
                            border = JBUI.Borders.emptyBottom(6)
                            font = font.deriveFont(font.size - 1f)
                            foreground = JBUI.CurrentTheme.Label.disabledForeground()
                        }
                        cardsPanel.add(summaryLabel)
                        prs.forEach { pr ->
                            cardsPanel.add(buildPrCard(pr, repo))
                            cardsPanel.add(Box.createVerticalStrut(6))
                        }
                    }
                    cardsPanel.revalidate()
                    cardsPanel.repaint()
                    setStatus("")
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setStatus("Error: ${e.message?.take(60)}")
                    showError(e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun buildSummaryText(branch: String?, total: Int, awaiting: Int): String {
        val branchPart = if (branch != null) " → $branch" else ""
        return "Open PRs$branchPart: $total  ·  awaiting review: $awaiting"
    }

    private fun buildPrCard(pr: PullRequest, repo: String): JPanel {
        val card = JPanel(GridBagLayout()).apply {
            border = CompoundBorder(
                BorderFactory.createLineBorder(
                    JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
                    1,
                    true
                ),
                JBUI.Borders.empty(8, 10)
            )
            // FIX: hug content height — don't allow vertical stretch
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = LEFT_ALIGNMENT
        }

        val gbc = GridBagConstraints().apply {
            gridx = 0; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
            insets = Insets(0, 0, 2, 0)  // no left inset — panels handle their own
        }

        val numLabel = JBLabel("#${pr.number}").apply {
            font = Font(Font.MONOSPACED, Font.BOLD, font.size - 1)
            foreground = Color(24, 95, 165)
        }
        val titleLabel = JBLabel("<html><b>${escHtml(pr.title)}</b></html>")
        val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(numLabel)
            add(Box.createHorizontalStrut(4))  // manual gap instead of FlowLayout hgap
            add(titleLabel)
        }
        gbc.gridy = 0; card.add(titlePanel, gbc)

        val metaText = "${pr.headRefName} → ${pr.baseRefName}  ·  ${pr.author}  ·  ${pr.timeAgo()}"
        val metaLabel = JBLabel(metaText).apply {
            font = font.deriveFont(font.size - 2f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border = JBUI.Borders.emptyTop(1)  // add this for breathing room vs title
        }
        gbc.gridy = 1; card.add(metaLabel, gbc)

        val reviewText = when (pr.reviewState) {
            ReviewState.APPROVED -> "approved by ${pr.approvedBy.joinToString(", ")}"
            ReviewState.CHANGES_REQUESTED -> "changes requested"
            ReviewState.AWAITING -> "awaiting review"
        }
        val badgePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(-4)

            add(makeBadge(reviewText, pr.reviewState))

            if (pr.mergeable == MergeableState.CONFLICTING) {
                add(makeBadge("⚠ conflict", null))
            }

            pr.labels.forEach { (name, _) ->
                add(makeLabelBadge(name))
            }
        }

        gbc.gridy = 2; card.add(badgePanel, gbc)

        val isAuthor = pr.author == ghUser
        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(-5) // cancel FlowLayout's first-item left padding

            if (!isAuthor) {
                add(makeActionButton("approve") { doApprovePr(pr, repo) })
            }
            add(makeActionButton("merge") { doMergePr(pr, repo, false) })
            if (!isAuthor) {
                add(makeActionButton("approve + merge") { doMergePr(pr, repo, true) })
            }
            add(makeActionButton("view") {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI(pr.url))
                } catch (e: Exception) {
                    setStatus("Could not open browser")
                }
            })
        }

        gbc.gridy = 3; card.add(actionPanel, gbc)

        // Finalize max height after all children laid out
        card.addHierarchyListener {
            card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        }

        return card
    }

    private fun doApprovePr(pr: PullRequest, repo: String) {
        setStatus("Approving #${pr.number}…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = PrService.approvePr(repo, pr.number)
            SwingUtilities.invokeLater {
                if (result.isSuccess) {
                    setStatus("✓ Approved #${pr.number}"); refresh()
                } else setStatus("✗ ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun doMergePr(pr: PullRequest, repo: String, andApprove: Boolean) {
        val label = if (andApprove) "Approving + merging" else "Merging"
        setStatus("$label #${pr.number}…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = PrService.mergePr(repo, pr.number, andApprove)
            SwingUtilities.invokeLater {
                if (result.isSuccess) {
                    setStatus("✓ Merged #${pr.number}"); refresh()
                } else setStatus("✗ ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun setStatus(text: String) {
        statusLabel.text = text
    }

    private fun showError(msg: String) {
        val label = JBLabel("<html>${escHtml(msg)}</html>").apply {
            border = JBUI.Borders.empty(12, 4)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }
        cardsPanel.removeAll()
        cardsPanel.add(label)
        cardsPanel.revalidate()
        cardsPanel.repaint()
    }

    private fun makeBadge(text: String, state: ReviewState?): JLabel {
        val bg = when (state) {
            ReviewState.APPROVED -> Color(234, 243, 222)
            ReviewState.CHANGES_REQUESTED -> Color(250, 238, 218)
            ReviewState.AWAITING -> Color(230, 241, 251)
            null -> Color(252, 235, 235)
        }
        val fg = when (state) {
            ReviewState.APPROVED -> Color(59, 109, 17)
            ReviewState.CHANGES_REQUESTED -> Color(133, 79, 11)
            ReviewState.AWAITING -> Color(24, 95, 165)
            null -> Color(163, 45, 45)
        }
        return JLabel(text).apply {
            isOpaque = true; background = bg; foreground = fg
            font = font.deriveFont(font.size - 2f)
            border = JBUI.Borders.empty(2, 6)
        }
    }

    private fun makeLabelBadge(name: String): JLabel {
        return JLabel(name).apply {
            isOpaque = true
            background = Color(230, 241, 251)
            foreground = Color(24, 95, 165)
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size - 2)
            border = JBUI.Borders.empty(2, 6)
        }
    }

    private fun makeActionButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = font.deriveFont(font.size - 1f)
            isFocusPainted = false
            isContentAreaFilled = false  // remove default button background padding
            margin = JBUI.insets(2, 6)
            addActionListener { action() }
        }
    }

    private fun escHtml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
