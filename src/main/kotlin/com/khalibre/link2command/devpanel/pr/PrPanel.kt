package com.khalibre.link2command.devpanel.pr

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.common.BadgeUtils
import com.khalibre.link2command.devpanel.common.CardUtils
import com.khalibre.link2command.devpanel.config.AuthorCache
import com.khalibre.link2command.devpanel.config.DevConfig
import java.awt.*
import java.awt.event.ActionListener
import javax.swing.*

class PrPanel(
    private val project: Project,
    private val tabId: String,
    private val onLoadingChanged: (Boolean) -> Unit = {}
) : JPanel(BorderLayout()) {

    private val baseBranchCombo = JComboBox<String>()
    private val authorCombo = JComboBox<String>()
    private val avatarCache = mutableMapOf<String, ImageIcon?>()
    private val cardsPanel = JPanel()
    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }
    private var upstreamRepo: String? = null
    private var ghUser: String? = null

    // Remembered (persisted to disk) base-branch/author selections for this tab, used as the
    // fallback default the first time each combo is populated in this session.
    // hasSaved*Pref distinguishes "no file yet → use the configured default" from
    // "a file exists and explicitly says none was selected → respect that, no default".
    private var savedBaseBranch: String? = null
    private var hasSavedBaseBranchPref = false
    private var savedAuthor: String? = null
    private var hasSavedAuthorPref = false

    // The most recent full fetch (server-filtered by whatever base/author was selected at the
    // time). Used to render an instant, locally-filtered preview the moment the user changes
    // base/author again — before the authoritative re-fetch for the new filter comes back.
    private var lastLoadedPrs: List<PullRequest> = emptyList()
    private var lastLoadedRepo: String? = null

    private val baseBranchListener = ActionListener { onFilterChanged() }
    private val authorListener = ActionListener { onFilterChanged() }

    private val syncButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Fetch upstream branches (git fetch upstream --prune)"
        isFocusPainted = false; isBorderPainted = false; isContentAreaFilled = false
        preferredSize = Dimension(24, 24); minimumSize = Dimension(24, 24); maximumSize =
        Dimension(24, 24)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val authorSyncButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh author list"
        isFocusPainted = false; isBorderPainted = false; isContentAreaFilled = false
        preferredSize = Dimension(24, 24); minimumSize = Dimension(24, 24); maximumSize =
        Dimension(24, 24)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    init {
        border = JBUI.Borders.empty(8, 10)
        loadTabStateFromDisk()
        buildUi()
        // Both base-branch and author combos are populated asynchronously (they need a pooled-
        // thread fetch first). Don't fire the initial PR fetch until both are done and the
        // remembered selections have actually been applied — otherwise the first refresh() runs
        // against whatever the combos default to ("— none —"), not the restored filter.
        var pending = 2
        val onComboReady: () -> Unit = {
            pending--
            if (pending == 0) refresh()
        }
        loadUpstreamBranches(onComboReady)
        loadAuthors(onComboReady)
    }

    private fun tabStateDir(): java.io.File? =
        com.khalibre.link2command.devpanel.common.ProjectPaths.cwDir(project)
            ?.let { PrTabsStore.tabStateDir(it, tabId) }

    private fun loadTabStateFromDisk() {
        val dir = tabStateDir() ?: return
        val baseFile = java.io.File(dir, "base-branch")
        val authorFile = java.io.File(dir, "author")
        if (baseFile.exists()) {
            hasSavedBaseBranchPref = true
            savedBaseBranch = baseFile.readText().trim().takeIf { it.isNotBlank() }
        }
        if (authorFile.exists()) {
            hasSavedAuthorPref = true
            savedAuthor = authorFile.readText().trim().takeIf { it.isNotBlank() }
        }
    }

    /**
     * Always writes both files (even when the selection is "— none —") so a deliberate "none"
     * choice is recorded as such, rather than looking identical to "never picked anything yet"
     * — which would otherwise make a fresh IDE session fall back to the configured default
     * branch instead of respecting the explicit "none" the person chose last time.
     */
    private fun saveTabState() {
        val dir = tabStateDir() ?: return
        dir.mkdirs()
        val base = baseBranchCombo.selectedItem?.toString()?.takeIf { it != "— none —" }
        java.io.File(dir, "base-branch").writeText(base ?: "")
        hasSavedBaseBranchPref = true; savedBaseBranch = base

        val author = authorCombo.selectedItem?.toString()?.takeIf { it != "— none —" }
        java.io.File(dir, "author").writeText(author ?: "")
        hasSavedAuthorPref = true; savedAuthor = author
    }

    private fun buildUi() {
        val toolbar = JPanel(BorderLayout(6, 0)).apply {
            border = JBUI.Borders.emptyBottom(8)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    requestFocusInWindow()
                }
            })
        }

        baseBranchCombo.apply {
            addItem("— none —")
            preferredSize = Dimension(200, 28); maximumSize = Dimension(200, 28); minimumSize =
            Dimension(80, 28)
            addActionListener(baseBranchListener)
        }
        com.intellij.ui.ComboboxSpeedSearch.installOn(baseBranchCombo)

        authorCombo.apply {
            addItem("— none —")
            preferredSize = Dimension(160, 28); maximumSize = Dimension(160, 28)
            addActionListener(authorListener)
        }
        com.intellij.ui.ComboboxSpeedSearch.installOn(authorCombo)

        authorCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val login = value?.toString() ?: return this
                if (login == "— none —") {
                    icon = null; border = JBUI.Borders.empty(4, 6); return this
                }
                border = if (index > 0 && (index == 1 || (index == 2 && ghUser != null)))
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(
                            1,
                            0,
                            0,
                            0,
                            JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
                        ),
                        JBUI.Borders.empty(4, 6)
                    )
                else JBUI.Borders.empty(4, 6)
                when {
                    avatarCache.containsKey(login) -> icon = avatarCache[login]
                    else -> {
                        icon = null; avatarCache[login] = null
                        ApplicationManager.getApplication().executeOnPooledThread {
                            val img = try {
                                val raw =
                                    javax.imageio.ImageIO.read(java.net.URL("https://github.com/$login.png?size=32"))
                                if (raw != null) {
                                    val size = 16
                                    val circle = java.awt.image.BufferedImage(
                                        size,
                                        size,
                                        java.awt.image.BufferedImage.TYPE_INT_ARGB
                                    )
                                    val g = circle.createGraphics()
                                    g.setRenderingHint(
                                        java.awt.RenderingHints.KEY_ANTIALIASING,
                                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                                    )
                                    g.setRenderingHint(
                                        java.awt.RenderingHints.KEY_INTERPOLATION,
                                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
                                    )
                                    g.setRenderingHint(
                                        java.awt.RenderingHints.KEY_RENDERING,
                                        java.awt.RenderingHints.VALUE_RENDER_QUALITY
                                    )
                                    g.fillOval(0, 0, size, size)
                                    g.composite = java.awt.AlphaComposite.SrcIn
                                    g.drawImage(
                                        raw.getScaledInstance(
                                            size,
                                            size,
                                            java.awt.Image.SCALE_SMOOTH
                                        ), 0, 0, null
                                    )
                                    g.dispose()
                                    ImageIcon(circle)
                                } else null
                            } catch (e: Exception) {
                                null
                            }
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
            layout = BoxLayout(this, BoxLayout.X_AXIS); isOpaque = false
            add(JBLabel("base:").apply { font = font.deriveFont(font.size - 1f) })
            add(Box.createHorizontalStrut(4)); add(baseBranchCombo)
            add(Box.createHorizontalStrut(4)); add(syncButton)
            add(Box.createHorizontalStrut(12))
            add(JBLabel("author:").apply { font = font.deriveFont(font.size - 1f) })
            add(Box.createHorizontalStrut(4)); add(authorCombo)
            add(Box.createHorizontalStrut(4)); add(authorSyncButton)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    requestFocusInWindow()
                }
            })
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
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    requestFocusInWindow()
                }
            })
        }
        add(scroll, BorderLayout.CENTER)
    }

    fun loadUpstreamBranches(callback: (() -> Unit)? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val branches = GitService.fetchUpstreamBranchNames(project)
            SwingUtilities.invokeLater { populateBranchCombo(branches); callback?.invoke() }
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
            ?: if (hasSavedBaseBranchPref) savedBaseBranch?.takeIf { branches.contains(it) }
            else cfg.git.base_branch.takeIf { branches.contains(it) }
        val idx = if (preferred != null) (0 until baseBranchCombo.itemCount)
            .firstOrNull { baseBranchCombo.getItemAt(it) == preferred } else null
        baseBranchCombo.selectedIndex = idx ?: 0
        baseBranchCombo.revalidate()
        baseBranchCombo.addActionListener(baseBranchListener)
    }

    private fun loadAuthors(callback: (() -> Unit)? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val repo = upstreamRepo ?: PrService.upstreamRepo(project)
            if (repo == null) {
                SwingUtilities.invokeLater { callback?.invoke() }
                return@executeOnPooledThread
            }
            upstreamRepo = repo
            val cached = AuthorCache.load(repo)
            val authors = if (cached.isNotEmpty()) cached
            else GitService.fetchPrAuthors(repo).also { AuthorCache.save(repo, it) }
            SwingUtilities.invokeLater { populateAuthorCombo(authors); callback?.invoke() }
        }
    }

    private fun syncAuthors() {
        setAuthorSyncSpinning(true); setStatus("Refreshing authors…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val repo = upstreamRepo ?: PrService.upstreamRepo(project)
            if (repo == null) {
                SwingUtilities.invokeLater { setAuthorSyncSpinning(false); setStatus("No upstream remote found") }
                return@executeOnPooledThread
            }
            upstreamRepo = repo
            val authors = GitService.fetchPrAuthors(repo)
            AuthorCache.save(repo, authors)
            SwingUtilities.invokeLater {
                populateAuthorCombo(authors); setAuthorSyncSpinning(false); setStatus(
                ""
            )
            }
        }
    }

    private fun populateAuthorCombo(authors: List<AuthorCache.CachedAuthor>) {
        authorCombo.removeActionListener(authorListener)
        val previousSelection = authorCombo.selectedItem?.toString()
        authorCombo.removeAllItems(); authorCombo.addItem("— none —")
        val currentUser = ghUser ?: PrService.currentGhUser().also { ghUser = it }
        authors.sortedWith(compareByDescending { it.login == currentUser })
            .forEach { authorCombo.addItem(it.login) }
        val target = previousSelection?.takeIf { it != "— none —" } ?: savedAuthor
        val idx =
            (0 until authorCombo.itemCount).firstOrNull { authorCombo.getItemAt(it) == target }
        authorCombo.selectedIndex = idx ?: 0
        authorCombo.addActionListener(authorListener)
    }

    private var authorSpinTimer: Timer? = null

    private fun setAuthorSyncSpinning(spinning: Boolean) {
        authorSpinTimer?.stop(); authorSpinTimer = null
        if (spinning) {
            var frame = 0
            authorSpinTimer = Timer(120) {
                authorSyncButton.icon = spinIcons[frame++ % spinIcons.size]
            }.also { it.start() }
            authorSyncButton.isEnabled = false
        } else {
            authorSyncButton.icon = AllIcons.Actions.Refresh; authorSyncButton.isEnabled = true
        }
    }

    private fun fetchUpstreamAndReload() {
        setSyncSpinning(true); setStatus("Fetching upstream…")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val workDir = project.basePath?.let { java.io.File(it) }
                PrService.runCmd(listOf("git", "fetch", "upstream", "--prune"), workDir)
                val branches = GitService.fetchUpstreamBranchNames(project)
                SwingUtilities.invokeLater {
                    populateBranchCombo(branches); setSyncSpinning(false); setStatus(
                    ""
                )
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setSyncSpinning(false); setStatus(
                    "Fetch failed: ${
                        e.message?.take(
                            60
                        )
                    }"
                )
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
        spinTimer?.stop(); spinTimer = null
        if (spinning) {
            var frame = 0
            spinTimer = Timer(120) {
                syncButton.icon = spinIcons[frame++ % spinIcons.size]
            }.also { it.start() }
            syncButton.isEnabled = false
        } else {
            syncButton.icon = AllIcons.Actions.Refresh; syncButton.isEnabled = true
        }
    }

    /** Called when the base-branch or author combo changes: instant local preview, then reload. */
    private fun onFilterChanged() {
        saveTabState()
        applyLocalFilterPreview()
        refresh()
    }

    /**
     * Renders an immediate, client-side-filtered view of the last full fetch using the
     * *current* combo selections — gives instant feedback while the authoritative re-fetch
     * for the new filter is still in flight. Falls back to doing nothing if we have no cache
     * yet (e.g. the very first load), since [refresh] is about to populate one anyway.
     */
    private fun applyLocalFilterPreview() {
        val repo = lastLoadedRepo ?: return
        if (lastLoadedPrs.isEmpty()) return
        val selectedBranch = baseBranchCombo.selectedItem?.toString()?.takeIf { it != "— none —" }
        val selectedAuthor = authorCombo.selectedItem?.toString()?.takeIf { it != "— none —" }
        val filtered = lastLoadedPrs.filter { pr ->
            (selectedBranch == null || pr.baseRefName == selectedBranch) &&
                    (selectedAuthor == null || pr.author == selectedAuthor)
        }
        renderPrList(filtered, repo, selectedBranch)
    }

    private fun renderPrList(prs: List<PullRequest>, repo: String, selectedBranch: String?) {
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
            val cfg = DevConfig.load()
            prs.forEach { pr ->
                cardsPanel.add(buildPrCard(pr, repo, cfg.git.can_merge)); cardsPanel.add(
                Box.createVerticalStrut(6)
            )
            }
        }
        cardsPanel.revalidate(); cardsPanel.repaint()
    }

    fun refresh() {
        onLoadingChanged(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (upstreamRepo == null) upstreamRepo = PrService.upstreamRepo(project)
                if (ghUser == null) ghUser = PrService.currentGhUser()
                val repo = upstreamRepo
                if (repo == null) {
                    SwingUtilities.invokeLater {
                        onLoadingChanged(false)
                        setStatus("No upstream remote found"); cardsPanel.removeAll()
                        cardsPanel.revalidate(); cardsPanel.repaint()
                        showError("Could not determine upstream repo.\nMake sure you have an 'upstream' git remote.")
                    }
                    return@executeOnPooledThread
                }
                val selectedBranch =
                    baseBranchCombo.selectedItem?.toString()?.takeIf { it != "— none —" }
                val selectedAuthor =
                    authorCombo.selectedItem?.toString()?.takeIf { it != "— none —" }
                val prs = PrService.fetchPrs(repo, selectedBranch, selectedAuthor)
                val mergeableMap = PrService.fetchMergeableStates(repo, prs.map { it.number })
                prs.forEach {
                    val info = mergeableMap[it.number]
                    it.mergeable = info?.mergeable ?: MergeableState.UNKNOWN
                    it.isOutdated = info?.isOutdated ?: false
                }
                SwingUtilities.invokeLater {
                    lastLoadedPrs = prs
                    lastLoadedRepo = repo
                    renderPrList(prs, repo, selectedBranch)
                    setStatus("")
                    onLoadingChanged(false)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    onLoadingChanged(false)
                    setStatus("Error: ${e.message?.take(60)}"); cardsPanel.removeAll()
                    cardsPanel.revalidate(); cardsPanel.repaint(); showError(
                    e.message ?: "Unknown error"
                )
                }
            }
        }
    }

    private fun buildSummaryText(branch: String?, total: Int, awaiting: Int): String {
        val branchPart = if (branch != null) " → $branch" else ""
        return "Open PRs$branchPart: $total  ·  awaiting review: $awaiting"
    }

    private fun buildPrCard(pr: PullRequest, repo: String, hasMergePermission: Boolean): JPanel {
        val card = CardUtils.makeCard("${pr.number}", "pr")
        val gbc = CardUtils.cardGbc()

        val titlePanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val g = GridBagConstraints()
            g.gridx = 0; g.gridy = 0; g.weightx = 0.0; g.fill = GridBagConstraints.NONE; g.insets =
            Insets(0, 0, 0, 4)
            add(JBLabel("#${pr.number}").apply {
                font = Font(Font.MONOSPACED, Font.BOLD, font.size - 1); foreground =
                Color(24, 95, 165)
            }, g)
            g.gridx = 1; g.weightx = 1.0; g.fill = GridBagConstraints.HORIZONTAL; g.insets =
            Insets(0, 0, 0, 0)
            add(JBLabel(pr.title).apply {
                font = font.deriveFont(Font.BOLD); minimumSize = Dimension(0, preferredSize.height)
                toolTipText = pr.title
            }, g)
        }
        gbc.gridy = 0; card.add(titlePanel, gbc)

        val isAuthor = pr.author == ghUser
        val authorColor = if (isAuthor) Color(59, 109, 17) else Color(24, 95, 165)
        val metaPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false; border = JBUI.Borders.emptyTop(1)
            add(JBLabel("${pr.headRefName} → ${pr.baseRefName}  ·  ").apply {
                font = font.deriveFont(font.size - 2f); foreground =
                JBUI.CurrentTheme.Label.disabledForeground()
            })
            add(JBLabel(pr.author).apply {
                font = font.deriveFont(font.size - 2f); foreground = authorColor
            })
            add(JBLabel("  ·  ${pr.timeAgo()}").apply {
                font = font.deriveFont(font.size - 2f); foreground =
                JBUI.CurrentTheme.Label.disabledForeground()
            })
        }
        gbc.gridy = 1; card.add(metaPanel, gbc)

        val reviewText = when (pr.reviewState) {
            ReviewState.APPROVED -> "✓ ${truncateNames(pr.approvedBy)}"
            ReviewState.CHANGES_REQUESTED -> "✗ ${truncateNames(pr.changesRequestedBy)}"
            ReviewState.COMMENTED -> "💬 ${truncateNames(pr.commentedBy)}"
            ReviewState.AWAITING -> "⏳ review"
        }
        val reviewTooltip = when (pr.reviewState) {
            ReviewState.APPROVED -> "Approved by ${truncateNames(pr.approvedBy)}"
            ReviewState.CHANGES_REQUESTED -> "Changes requested by ${truncateNames(pr.changesRequestedBy)}"
            ReviewState.COMMENTED -> "Unresolved comments from ${truncateNames(pr.commentedBy)}"
            ReviewState.AWAITING -> "Awaiting review"
        }
        val badgePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false; border = JBUI.Borders.emptyLeft(-4)
            add(makeBadge(reviewText, pr.reviewState, reviewTooltip))
            if (pr.mergeable == MergeableState.CONFLICTING) {
                add(
                    makeBadge(
                        "⚠ conflict",
                        null,
                        "This branch has merge conflicts with ${pr.baseRefName}"
                    )
                )
            } else if (pr.isOutdated) {
                val tooltip = if (isAuthor) {
                    "Your branch is out of date with ${pr.baseRefName}. Update it to include the latest changes."
                } else {
                    "This branch is out of date with ${pr.baseRefName}."
                }
                add(
                    makeBadge(
                        "ⓘ outdated",
                        null,
                        tooltip,
                        bg = Color(240, 230, 255),
                        fg = Color(90, 50, 140)
                    )
                )
            }
            pr.labels.forEach { (name, color) -> add(makeLabelBadge(name, color)) }
        }
        gbc.gridy = 2; card.add(badgePanel, gbc)

        val actionPanel = JPanel(
            com.khalibre.link2command.devpanel.tickets.WrapLayout(
                FlowLayout.LEFT,
                2,
                2
            )
        ).apply {
            isOpaque = false; border = JBUI.Borders.emptyLeft(-4)
            add(makeActionButton("View") {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI(pr.url))
                } catch (e: Exception) {
                    setStatus("Could not open browser")
                }
            }.apply {
                toolTipText = if (isAuthor) "Open your PR in browser" else "Open this PR in browser"
            })

            lateinit var copyLinkBtn: JButton
            copyLinkBtn = makeActionButton("Copy link") {
                CardUtils.copyToClipboardWithBalloon(
                    pr.url, copyLinkBtn, "Link copied"
                )
            }
            add(copyLinkBtn)

            if (isAuthor) {
                add(makeActionButton("Checkout") { doCheckout(pr, repo) }.apply {
                    toolTipText = "Checkout this PR branch"
                })
                val rebaseButton = makeActionButton("Rebase") {}.apply {
                    toolTipText = "Checkout this PR branch and rebase it from ${pr.baseRefName}"
                }
                rebaseButton.addActionListener { doRebase(pr, rebaseButton) }
                add(rebaseButton)
                val updatePrButton = makeActionButton("Update PR") { }.apply {
                    toolTipText = "Update PR description, remove dependency text, add image, etc."
                }
                updatePrButton.addActionListener { doUpdatePr(pr, updatePrButton) }
                add(updatePrButton)
            } else {
                add(makeActionButton("Approve") { doApprovePr(pr, repo) })
                if (hasMergePermission) {
                    add(makeActionButton("Approve + Merge") { doMergePr(pr, repo, true) }.apply {
                        toolTipText = "Approve and merge in one step"
                    })
                }
            }

            if (hasMergePermission) {
                add(makeActionButton("Merge") { doMergePr(pr, repo, false) }.apply {
                    toolTipText = "Merge this PR into ${pr.baseRefName}"
                })
            }
        }
        gbc.gridy = 3; card.add(actionPanel, gbc)

        // Sync card max-height whenever actionPanel reflows (wrapping changes height)
        val syncHeight: (java.awt.event.ComponentEvent?) -> Unit = {
            val ph = card.preferredSize.height
            if (card.maximumSize.height != ph) {
                card.maximumSize = Dimension(Int.MAX_VALUE, ph)
                card.revalidate()
            }
        }
        actionPanel.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = syncHeight(e)
        })
        card.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = syncHeight(e)
        })

        // Sync immediately too: if WrapLayout already reports the correct wrapped height on
        // this very first layout pass, componentResized never fires (the size doesn't change),
        // so without this the card would be left hugging its initial (often wrong) bounds.
        // A couple of deferred passes catch the case where the card isn't fully sized/showing yet.
        syncHeight(null)
        SwingUtilities.invokeLater {
            syncHeight(null)
            SwingUtilities.invokeLater { syncHeight(null) }
        }
        return card
    }

    private fun truncateNames(names: List<String>): String {
        if (names.size <= 2) return names.joinToString(", ")
        return "${names.take(1).joinToString(", ")} +${names.size - 1} more"
    }

    private fun doCheckout(pr: PullRequest, repo: String) {
        setStatus("Checking out ${pr.headRefName}…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = PrService.checkoutBranch(project, pr.headRefName)
            SwingUtilities.invokeLater {
                if (result.isSuccess) setStatus("✓ Checked out ${pr.headRefName}") else setStatus(
                    "✗ ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    private fun doRebase(pr: PullRequest, anchor: Component) {
        setStatus("Rebasing ${pr.headRefName}…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = PrService.rebasePr(project, pr.headRefName, pr.baseRefName)
            SwingUtilities.invokeLater {
                setStatus("")
                if (result.isSuccess) {
                    CardUtils.showResultBalloon(
                        "✓ Rebased <b>${pr.headRefName}</b>",
                        anchor,
                        MessageType.INFO
                    )
                } else {
                    CardUtils.showResultBalloon(
                        "✗ ${CardUtils.escHtml(result.exceptionOrNull()?.message ?: "Rebase failed")}",
                        anchor,
                        MessageType.ERROR
                    )
                }
                refresh()
            }
        }
    }

    private fun doUpdatePr(pr: PullRequest, anchor: Component) {
        setStatus("Updating PR #${pr.number}…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = PrService.updatePr(project, pr.number)
            SwingUtilities.invokeLater {
                setStatus("")
                if (result.isSuccess) {
                    CardUtils.showResultBalloon(
                        "✓ PR #${pr.number} updated",
                        anchor,
                        MessageType.INFO
                    )
                } else {
                    CardUtils.showResultBalloon(
                        "✗ ${CardUtils.escHtml(result.exceptionOrNull()?.message ?: "Update failed")}",
                        anchor,
                        MessageType.ERROR
                    )
                }
            }
        }
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
        cardsPanel.add(JBLabel("<html>${CardUtils.escHtml(msg)}</html>").apply {
            border = JBUI.Borders.empty(12, 4)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        })
        cardsPanel.revalidate(); cardsPanel.repaint()
    }

    private fun makeBadge(
        text: String,
        state: ReviewState?,
        tooltip: String? = null,
        bg: Color? = null,
        fg: Color? = null
    ): JLabel {
        val resolvedBg = bg ?: when (state) {
            ReviewState.APPROVED -> Color(234, 243, 222)
            ReviewState.CHANGES_REQUESTED -> Color(250, 238, 218)
            ReviewState.COMMENTED -> Color(235, 235, 250)
            ReviewState.AWAITING -> Color(230, 241, 251)
            null -> Color(252, 235, 235)
        }
        val resolvedFg = fg ?: when (state) {
            ReviewState.APPROVED -> Color(59, 109, 17)
            ReviewState.CHANGES_REQUESTED -> Color(133, 79, 11)
            ReviewState.COMMENTED -> Color(88, 60, 163)
            ReviewState.AWAITING -> Color(24, 95, 165)
            null -> Color(163, 45, 45)
        }
        return BadgeUtils.makeBadge(text, resolvedBg, resolvedFg).apply {
            toolTipText = tooltip
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
        return BadgeUtils.makeBadge(name, bg, fg)
    }

    private fun makeActionButton(text: String, action: () -> Unit) =
        CardUtils.makeActionButton(text, action)

    private fun escHtml(s: String) = CardUtils.escHtml(s)
}
