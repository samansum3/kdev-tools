package com.khalibre.link2command.devpanel.pr

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.config.AuthorCache
import com.khalibre.link2command.devpanel.config.DevConfig
import java.awt.*
import java.awt.event.ActionListener
import javax.swing.*
import javax.swing.border.CompoundBorder

class PrPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val baseBranchCombo = JComboBox<String>()
    private val authorCombo = JComboBox<String>()
    private val cardsPanel = JPanel()
    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }
    private var upstreamRepo: String? = null
    private var ghUser: String? = null

    // Declared once — same instance reattached, never a new lambda
    private val baseBranchListener = ActionListener { refresh() }
    private val authorListener = ActionListener { refresh() }

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
    private val authorSyncButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh author list"
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
        loadAuthors()
    }

    private fun buildUi() {
        val toolbar = JPanel(BorderLayout(6, 0)).apply {
            border = JBUI.Borders.emptyBottom(8)
        }

        baseBranchCombo.apply {
            addItem("— none —")
            preferredSize = Dimension(200, 28)
            maximumSize = Dimension(200, 28)
            minimumSize = Dimension(80, 28)
            addActionListener(baseBranchListener)
        }

        authorCombo.apply {
            addItem("— none —")
            preferredSize = Dimension(160, 28)
            maximumSize = Dimension(160, 28)
            addActionListener(authorListener)
        }

        // Render Github profile avatar
        authorCombo.renderer = object : DefaultListCellRenderer() {
            private val avatarCache = mutableMapOf<String, ImageIcon?>()

            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val login = value?.toString() ?: return this

                if (login == "— none —") {
                    icon = null
                    border = JBUI.Borders.empty(4, 6)
                    return this
                }

                // separator line: after "— none —" (index 1), and after current user (index 2 if ghUser exists)
                border = if (index > 0 && (index == 1 || (index == 2 && ghUser != null)))
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                        JBUI.Borders.empty(4, 6)
                    )
                else JBUI.Borders.empty(4, 6)

                // circular avatar 20px, fetched at 32px to avoid pixelation
                when {
                    avatarCache.containsKey(login) -> icon = avatarCache[login]
                    else -> {
                        icon = null
                        avatarCache[login] = null
                        ApplicationManager.getApplication().executeOnPooledThread {
                            val img = try {
                                val raw = javax.imageio.ImageIO.read(java.net.URL("https://github.com/$login.png?size=32"))
                                if (raw != null) {
                                    val size = 16
                                    val circle = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                                    val g = circle.createGraphics()
                                    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                                    g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
                                    // draw circle mask
                                    g.fillOval(0, 0, size, size)
                                    // switch to SRC_IN so image is clipped to the circle shape with smooth edges
                                    g.composite = java.awt.AlphaComposite.SrcIn
                                    g.drawImage(raw.getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
                                    g.dispose()
                                    ImageIcon(circle)
                                } else null
                            } catch (e: Exception) { null }
                            avatarCache[login] = img
                            SwingUtilities.invokeLater { authorCombo.repaint() }
                        }
                    }
                }

                return this
            }
        }

        syncButton.addActionListener { fetchUpstreamAndReload() }
        authorSyncButton.addActionListener { syncAuthors() }

        val branchRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(JBLabel("base:").apply { font = font.deriveFont(font.size - 1f) })
            add(Box.createHorizontalStrut(4))
            add(baseBranchCombo)
            add(Box.createHorizontalStrut(4))
            add(syncButton)
            add(Box.createHorizontalStrut(12))
            add(JBLabel("author:").apply { font = font.deriveFont(font.size - 1f) })
            add(Box.createHorizontalStrut(4))
            add(authorCombo)
            add(Box.createHorizontalStrut(4))
            add(authorSyncButton)
        }

        toolbar.add(branchRow, BorderLayout.CENTER)
        toolbar.add(statusLabel, BorderLayout.SOUTH)
        add(toolbar, BorderLayout.NORTH)

        cardsPanel.layout = BoxLayout(cardsPanel, BoxLayout.Y_AXIS)
        cardsPanel.border = JBUI.Borders.empty()

        val scroll = JBScrollPane(cardsPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)
    }

    fun loadUpstreamBranches(callback: (() -> Unit)? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val branches = GitService.fetchUpstreamBranchNames(project)
            SwingUtilities.invokeLater {
                populateBranchCombo(branches)
                callback?.invoke()
            }
        }
    }

    private fun populateBranchCombo(branches: List<String>) {
        baseBranchCombo.removeActionListener(baseBranchListener)

        val previousSelection = baseBranchCombo.selectedItem?.toString()
        baseBranchCombo.removeAllItems()
        baseBranchCombo.addItem("— none —")
        branches.forEach { baseBranchCombo.addItem(it) }

        val cfg = DevConfig.load()
        val preferred = previousSelection?.takeIf { it != "— none —" && branches.contains(it) }
            ?: cfg.git.base_branch.takeIf { branches.contains(it) }
        val idx = if (preferred != null) (0 until baseBranchCombo.itemCount)
            .firstOrNull { baseBranchCombo.getItemAt(it) == preferred } else null
        baseBranchCombo.selectedIndex = idx ?: 0
        baseBranchCombo.revalidate()

        baseBranchCombo.addActionListener(baseBranchListener)
    }

    private fun loadAuthors() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val repo = upstreamRepo ?: PrService.upstreamRepo(project) ?: return@executeOnPooledThread
            upstreamRepo = repo
            val cached = AuthorCache.load(repo)
            val authors = if (cached.isNotEmpty()) cached
            else GitService.fetchPrAuthors(repo).also { AuthorCache.save(repo, it) }
            SwingUtilities.invokeLater { populateAuthorCombo(authors) }
        }
    }

    private fun syncAuthors() {
        setAuthorSyncSpinning(true)
        setStatus("Refreshing authors…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val repo = upstreamRepo ?: PrService.upstreamRepo(project)
            if (repo == null) {
                SwingUtilities.invokeLater {
                    setAuthorSyncSpinning(false)
                    setStatus("No upstream remote found")
                }
                return@executeOnPooledThread
            }
            upstreamRepo = repo
            val authors = GitService.fetchPrAuthors(repo)
            AuthorCache.save(repo, authors)
            SwingUtilities.invokeLater {
                populateAuthorCombo(authors)
                setAuthorSyncSpinning(false)
                setStatus("")
            }
        }
    }

    private fun populateAuthorCombo(authors: List<AuthorCache.CachedAuthor>) {
        authorCombo.removeActionListener(authorListener)
        val previousSelection = authorCombo.selectedItem?.toString()

        authorCombo.removeAllItems()
        authorCombo.addItem("— none —")

        // pin current user first if they're in the list
        val currentUser = ghUser ?: PrService.currentGhUser().also { ghUser = it }
        val sorted = authors.sortedWith(compareByDescending { it.login == currentUser })
        sorted.forEach { authorCombo.addItem(it.login) }

        val idx = (0 until authorCombo.itemCount)
            .firstOrNull { authorCombo.getItemAt(it) == previousSelection }
        authorCombo.selectedIndex = idx ?: 0
        authorCombo.addActionListener(authorListener)
    }

    private var authorSpinTimer: Timer? = null

    private fun setAuthorSyncSpinning(spinning: Boolean) {
        authorSpinTimer?.stop()
        authorSpinTimer = null
        if (spinning) {
            var frame = 0
            authorSpinTimer = Timer(120) {
                authorSyncButton.icon = spinIcons[frame % spinIcons.size]
                frame++
            }.also { it.start() }
            authorSyncButton.isEnabled = false
        } else {
            authorSyncButton.icon = AllIcons.Actions.Refresh
            authorSyncButton.isEnabled = true
        }
    }

    private fun fetchUpstreamAndReload() {
        setSyncSpinning(true)
        setStatus("Fetching upstream…")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val workDir = project.basePath?.let { java.io.File(it) }
                PrService.runCmd(listOf("git", "fetch", "upstream", "--prune"), workDir)
                val branches = GitService.fetchUpstreamBranchNames(project)
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
                val selectedAuthor = authorCombo.selectedItem?.toString()
                    ?.takeIf { it != "— none —" }

                val prs = PrService.fetchPrs(repo, selectedBranch, selectedAuthor)
                val mergeableMap = PrService.fetchMergeableStates(repo, prs.map { it.number })
                prs.forEach { it.mergeable = mergeableMap[it.number] ?: MergeableState.UNKNOWN }

                SwingUtilities.invokeLater {
                    // NO author combo population here — authors are managed by loadAuthors() only
                    cardsPanel.removeAll()
                    if (prs.isEmpty()) {
                        cardsPanel.add(JBLabel("No open PRs found").apply {
                            border = JBUI.Borders.empty(16, 4)
                            foreground = JBUI.CurrentTheme.Label.disabledForeground()
                        })
                    } else {
                        val awaitingCount = prs.count { it.reviewState == ReviewState.AWAITING }
                        cardsPanel.add(
                            JBLabel(
                                buildSummaryText(
                                    selectedBranch,
                                    prs.size,
                                    awaitingCount
                                )
                            ).apply {
                                border = JBUI.Borders.emptyBottom(6)
                                font = font.deriveFont(font.size - 1f)
                                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                            })
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
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = LEFT_ALIGNMENT
        }

        val gbc = GridBagConstraints().apply {
            gridx = 0; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
            insets = Insets(0, 0, 2, 0)
        }

        val titlePanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val g = GridBagConstraints()

            // PR number — fixed width
            g.gridx = 0; g.gridy = 0; g.weightx = 0.0; g.fill = GridBagConstraints.NONE
            g.insets = Insets(0, 0, 0, 4)
            add(JBLabel("#${pr.number}").apply {
                font = Font(Font.MONOSPACED, Font.BOLD, font.size - 1)
                foreground = Color(24, 95, 165)
            }, g)

            // Title — takes remaining width and truncates
            g.gridx = 1; g.weightx = 1.0; g.fill = GridBagConstraints.HORIZONTAL
            g.insets = Insets(0, 0, 0, 0)
            add(JBLabel(pr.title).apply {
                font = font.deriveFont(Font.BOLD)
                minimumSize = Dimension(0, preferredSize.height)
            }, g)
        }
        gbc.gridy = 0; card.add(titlePanel, gbc)

        gbc.gridy =
            1; card.add(JBLabel("${pr.headRefName} → ${pr.baseRefName}  ·  ${pr.author}  ·  ${pr.timeAgo()}").apply {
            font = font.deriveFont(font.size - 2f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border = JBUI.Borders.emptyTop(1)
        }, gbc)

        val reviewText = when (pr.reviewState) {
            ReviewState.APPROVED -> "approved by ${pr.approvedBy.joinToString(", ")}"
            ReviewState.CHANGES_REQUESTED -> "changes requested"
            ReviewState.AWAITING -> "awaiting review"
        }
        val badgePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(-4)
            add(makeBadge(reviewText, pr.reviewState))
            if (pr.mergeable == MergeableState.CONFLICTING) add(makeBadge("⚠ conflict", null))
            pr.labels.forEach { (name, color) -> add(makeLabelBadge(name, color)) }
        }
        gbc.gridy = 2; card.add(badgePanel, gbc)

        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(-5)
            val isAuthor = pr.author == ghUser
            if (!isAuthor) add(makeActionButton("approve") { doApprovePr(pr, repo) })
            add(makeActionButton("merge") { doMergePr(pr, repo, false) })
            if (!isAuthor) add(makeActionButton("approve + merge") { doMergePr(pr, repo, true) })
            add(makeActionButton("view") {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI(pr.url))
                } catch (e: Exception) {
                    setStatus("Could not open browser")
                }
            })
        }
        gbc.gridy = 3; card.add(actionPanel, gbc)

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

    private fun makeLabelBadge(name: String, hexColor: String): JLabel {
        val bg = try {
            Color(Integer.parseInt(hexColor, 16))
        } catch (e: Exception) {
            Color(0x8B949E)
        }
        val luminance = (0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue) / 255
        val fg = if (luminance > 0.5) Color(0x1F2328) else Color.WHITE
        return JLabel(name).apply {
            isOpaque = true; background = bg; foreground = fg
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size - 2)
            border = JBUI.Borders.empty(2, 6)
        }
    }

    private fun makeActionButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = font.deriveFont(font.size - 1f)
            isFocusPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(2, 6)
            addActionListener { action() }
        }
    }

    private fun escHtml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
