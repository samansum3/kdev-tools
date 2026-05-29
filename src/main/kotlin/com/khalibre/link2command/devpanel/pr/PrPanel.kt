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

    init {
        border = JBUI.Borders.empty(8, 10)
        buildUi()
    }

    private fun buildUi() {
        // ── Top toolbar: base branch dropdown ─────────────────────────────
        val toolbar = JPanel(BorderLayout(6, 0)).apply {
            border = JBUI.Borders.emptyBottom(8)
        }

        val branchLabel = JBLabel("base:").apply {
            font = font.deriveFont(font.size - 1f)
        }
        baseBranchCombo.apply {
            addItem("— none —")
            val cfg = DevConfig.load()
            if (cfg.git.base_branch.isNotBlank()) addItem(cfg.git.base_branch)
            addItem("main"); addItem("develop"); addItem("master")
            // select configured branch
            val idx = (0 until itemCount).firstOrNull { getItemAt(it) == cfg.git.base_branch } ?: 0
            selectedIndex = if (idx >= 0) idx else 0
            addActionListener { refresh() }
            maximumSize = Dimension(Int.MAX_VALUE, 28)
        }

        val branchRow = JPanel(BorderLayout(4, 0))
        branchRow.add(branchLabel, BorderLayout.WEST)
        branchRow.add(baseBranchCombo, BorderLayout.CENTER)
        toolbar.add(branchRow, BorderLayout.CENTER)
        toolbar.add(statusLabel, BorderLayout.SOUTH)

        add(toolbar, BorderLayout.NORTH)

        // ── Cards list ────────────────────────────────────────────────────
        cardsPanel.layout = BoxLayout(cardsPanel, BoxLayout.Y_AXIS)
        cardsPanel.border = JBUI.Borders.empty()

        val scroll = JBScrollPane(cardsPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)
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

                // fetch mergeable states in parallel
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
                        val summaryLabel = JBLabel(buildSummaryText(selectedBranch, prs.size, awaitingCount)).apply {
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
                BorderFactory.createLineBorder(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, true),
                JBUI.Borders.empty(8, 10)
            )
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            alignmentX = LEFT_ALIGNMENT
        }
        val gbc = GridBagConstraints().apply {
            gridx = 0; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
            insets = JBUI.insets(1, 0, 1, 0)
        }

        // ── PR number + title ─────────────────────────────────────────────
        val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        val numLabel = JBLabel("#${pr.number}").apply {
            font = Font(Font.MONOSPACED, Font.BOLD, font.size - 1)
            foreground = Color(24, 95, 165)
        }
        val titleLabel = JBLabel("<html><b>${escHtml(pr.title)}</b></html>")
        titlePanel.add(numLabel)
        titlePanel.add(titleLabel)
        gbc.gridy = 0; card.add(titlePanel, gbc)

        // ── Branch + author + time ────────────────────────────────────────
        val metaText = "${pr.headRefName} → ${pr.baseRefName}  ·  ${pr.author}  ·  ${pr.timeAgo()}"
        val metaLabel = JBLabel(metaText).apply {
            font = font.deriveFont(font.size - 2f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }
        gbc.gridy = 1; card.add(metaLabel, gbc)

        // ── Review status + labels + conflict ─────────────────────────────
        val badgePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { isOpaque = false }

        // review badge
        val reviewText = when (pr.reviewState) {
            ReviewState.APPROVED -> "approved by ${pr.approvedBy.joinToString(", ")}"
            ReviewState.CHANGES_REQUESTED -> "changes requested"
            ReviewState.AWAITING -> "awaiting review"
        }
        badgePanel.add(makeBadge(reviewText, pr.reviewState))

        // conflict badge
        if (pr.mergeable == MergeableState.CONFLICTING) {
            badgePanel.add(makeBadge("⚠ conflict", null))
        }

        // label badges (BE, FE, etc.)
        pr.labels.forEach { (name, _) ->
            badgePanel.add(makeLabelBadge(name))
        }

        gbc.gridy = 2; card.add(badgePanel, gbc)

        // ── Action buttons ────────────────────────────────────────────────
        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        val isAuthor = pr.author == ghUser

        if (!isAuthor) {
            actionPanel.add(makeActionButton("approve") { doApprovePr(pr, repo) })
        }
        actionPanel.add(makeActionButton("merge") { doMergePr(pr, repo, false) })
        if (!isAuthor) {
            actionPanel.add(makeActionButton("merge+approve") { doMergePr(pr, repo, true) })
        }
        actionPanel.add(makeActionButton("view") { PrService.openInBrowser(pr.number) })

        gbc.gridy = 3; card.add(actionPanel, gbc)

        return card
    }

    private fun doApprovePr(pr: PullRequest, repo: String) {
        setStatus("Approving #${pr.number}…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = PrService.approvePr(repo, pr.number)
            SwingUtilities.invokeLater {
                if (result.isSuccess) {
                    setStatus("✓ Approved #${pr.number}")
                    refresh()
                } else {
                    setStatus("✗ ${result.exceptionOrNull()?.message}")
                }
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
                    setStatus("✓ Merged #${pr.number}")
                    refresh()
                } else {
                    setStatus("✗ ${result.exceptionOrNull()?.message}")
                }
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
            null -> Color(252, 235, 235) // conflict - red tint
        }
        val fg = when (state) {
            ReviewState.APPROVED -> Color(59, 109, 17)
            ReviewState.CHANGES_REQUESTED -> Color(133, 79, 11)
            ReviewState.AWAITING -> Color(24, 95, 165)
            null -> Color(163, 45, 45)
        }
        return JLabel(text).apply {
            isOpaque = true
            background = bg
            foreground = fg
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
            margin = JBUI.insets(2, 8)
            addActionListener { action() }
        }
    }

    private fun escHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
