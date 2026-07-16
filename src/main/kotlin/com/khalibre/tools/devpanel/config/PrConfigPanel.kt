package com.khalibre.tools.devpanel.config

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.khalibre.tools.devpanel.pr.GitService
import com.khalibre.tools.devpanel.pr.PrService
import com.khalibre.tools.devpanel.tickets.TicketsPanel
import com.khalibre.tools.devpanel.tickets.WrapLayout
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*

/**
 * "PR Config" sub-tab — everything previously under Config > General > GIT, minus the unused
 * Stack remote field, plus the default-reviewers list upgraded from free text to avatar badge
 * toggles (same author list / avatar-loading approach as PR Tools' author combo).
 */
class PrConfigPanel(
    private val project: Project,
    private val getCwDir: () -> File?
) : JPanel(BorderLayout()) {

    private val baseBranchCombo = JComboBox<String>()
    private val userSessionField = JBPasswordField()
    private val avatarCache = mutableMapOf<String, ImageIcon?>()

    private var allAuthors: List<AuthorCache.CachedAuthor> = emptyList()
    private var reviewerBadges: List<JLabel> = emptyList()
    private val reviewersWrap = JPanel(WrapLayout(FlowLayout.LEFT, 4, 3)).apply { isOpaque = false }
    private lateinit var reviewersHeaderTextLabel: JLabel
    private var reviewersExpanded = true   // default expanded; overwritten by persisted UI state

    private val hasMergePermission = makeConfigBadge("Yes", true)
    private val noMergePermission = makeConfigBadge("No", false)

    private var upstreamRepo: String? = null

    private val statusLabel = JBLabel("").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        font = font.deriveFont(font.size - 1f)
    }

    init {
        border = JBUI.Borders.empty(10, 12)
        loadUiStateFromDisk()
        buildUi()
        wireMergePermissionExclusivity()
        loadConfig()
        loadUpstreamBranches()
        loadAuthorsAndApplyConfig()
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private fun buildUi() {
        val form = JPanel(GridBagLayout())
        form.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = requestFocusInWindow().let {}
        })

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
                gridx = 0; gridy = row; gridwidth = 1
                fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
                insets = JBUI.insets(0)
            })
        }

        fullRow(0, fieldLabel("Base branch"))
        fullRow(1, baseBranchCombo.apply { maximumSize = Dimension(Int.MAX_VALUE, 28) })
        com.intellij.ui.ComboboxSpeedSearch.installOn(baseBranchCombo)

        fullRow(2, fieldLabel("User session (cookie for PR upload)"))
        fullRow(3, userSessionField)

        fullRow(4, fieldLabel("Has merge permission"))
        val itemModeRow = JPanel(WrapLayout(FlowLayout.LEFT, 4, 3)).apply {
            isOpaque = false; alignmentX = LEFT_ALIGNMENT
        }
        itemModeRow.add(noMergePermission)
        itemModeRow.add(hasMergePermission)
        fullRow(5, itemModeRow)
        fullRow(7, buildReviewersSection())


        // Spacer
        form.add(JPanel(), GridBagConstraints().apply {
            gridx = 0; gridy = 8; gridwidth = 1
            weighty = 1.0; fill = GridBagConstraints.BOTH
        })

        val scroll = JBScrollPane(form).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)

        val bottom = JPanel(BorderLayout()).apply { border = JBUI.Borders.emptyTop(8) }
        val saveBtn = JButton("Save PR Config").apply { addActionListener { saveConfig() } }
        bottom.add(saveBtn, BorderLayout.NORTH)
        bottom.add(
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(statusLabel) },
            BorderLayout.SOUTH
        )
        add(bottom, BorderLayout.SOUTH)
    }

    /** Header + wrap for the default-reviewers badge list. Expanded by default; the user's
     *  expand/collapse choice is persisted in .git/cw so it's remembered next session. */
    private fun buildReviewersSection(): JPanel {
        reviewersWrap.alignmentX = LEFT_ALIGNMENT
        reviewersWrap.isVisible = reviewersExpanded

        fun headerText(): String {
            val count = reviewerBadges.count { it.getClientProperty("active") == true }
            val cnt = if (count > 0) "  ($count)" else ""
            val caret = if (reviewersExpanded) "▾" else "▸"
            return "Default reviewers$cnt  $caret"
        }

        reviewersHeaderTextLabel = JLabel(headerText()).apply {
            font = font.deriveFont(font.size - 1f)
            foreground = JBUI.CurrentTheme.Label.foreground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val headerRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 24)
            add(reviewersHeaderTextLabel)
        }

        val clickHandler = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                reviewersExpanded = !reviewersExpanded
                reviewersWrap.isVisible = reviewersExpanded
                reviewersHeaderTextLabel.text = headerText()
                reviewersWrap.revalidate(); reviewersWrap.repaint()
                headerRow.parent?.revalidate(); headerRow.parent?.repaint()
                saveUiStateToDisk()
            }

            override fun mouseEntered(e: MouseEvent) {
                reviewersHeaderTextLabel.foreground = Color(24, 95, 165)
            }

            override fun mouseExited(e: MouseEvent) {
                reviewersHeaderTextLabel.foreground = JBUI.CurrentTheme.Label.foreground()
            }
        }
        headerRow.addMouseListener(clickHandler)
        reviewersHeaderTextLabel.addMouseListener(clickHandler)

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(headerRow)
            add(reviewersWrap)
        }
    }

    private fun refreshReviewersHeader() {
        val count = reviewerBadges.count { it.getClientProperty("active") == true }
        val cnt = if (count > 0) "  ($count)" else ""
        val caret = if (reviewersExpanded) "▾" else "▸"
        reviewersHeaderTextLabel.text = "Default reviewers$cnt  $caret"
    }

    /** Keeps exactly one of Yes/No active — clicking one turns the other off. */
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

    /** Reviewer badge: same toggle style as other config badges, plus an author avatar. */
    private fun makeReviewerBadge(author: AuthorCache.CachedAuthor, initiallyActive: Boolean): JLabel {
        val badge = makeConfigBadge(author.login, initiallyActive)
        badge.iconTextGap = 4
        loadAuthorAvatar(author.login) { icon ->
            badge.icon = icon
            badge.revalidate(); badge.repaint()
        }
        badge.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                SwingUtilities.invokeLater { refreshReviewersHeader() }
            }
        })
        return badge
    }

    /** Same avatar-loading approach as PR Tools' author combo: GitHub's `login.png`, cached
     *  in memory, rendered as a small circular icon. */
    private fun loadAuthorAvatar(login: String, onLoaded: (ImageIcon) -> Unit) {
        avatarCache[login]?.let { onLoaded(it); return }
        if (avatarCache.containsKey(login)) return // load already in flight / failed
        avatarCache[login] = null
        ApplicationManager.getApplication().executeOnPooledThread {
            val icon = try {
                val raw = javax.imageio.ImageIO.read(java.net.URL("https://github.com/$login.png?size=32"))
                if (raw != null) {
                    val size = 14
                    val circle = java.awt.image.BufferedImage(
                        size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB
                    )
                    val g = circle.createGraphics()
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR
                    )
                    g.fillOval(0, 0, size, size)
                    g.composite = AlphaComposite.SrcIn
                    g.drawImage(raw.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null)
                    g.dispose()
                    ImageIcon(circle)
                } else null
            } catch (e: Exception) {
                null
            }
            if (icon != null) {
                avatarCache[login] = icon
                SwingUtilities.invokeLater { onLoaded(icon) }
            }
        }
    }

    // ── Branch / author loading ─────────────────────────────────────────────────

    private fun loadUpstreamBranches() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val branches = GitService.fetchUpstreamBranchNames(project)
            SwingUtilities.invokeLater {
                val current = baseBranchCombo.selectedItem?.toString()
                baseBranchCombo.removeAllItems()
                baseBranchCombo.addItem("")
                branches.forEach { baseBranchCombo.addItem(it) }
                val cfg = DevConfig.load(getCwDir())
                val preferred = current?.takeIf { it.isNotBlank() && branches.contains(it) }
                    ?: cfg.git.base_branch.takeIf { branches.contains(it) }
                val idx = preferred?.let { p ->
                    (0 until baseBranchCombo.itemCount).firstOrNull { baseBranchCombo.getItemAt(it) == p }
                }
                baseBranchCombo.selectedIndex = idx ?: 0
            }
        }
    }

    /** Same author-list logic as PR Tools' author combo: cached list first, otherwise fetch
     *  collaborators/PR authors from GitHub and cache them. */
    private fun loadAuthorsAndApplyConfig() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val repo = upstreamRepo ?: PrService.upstreamRepo(project)
            if (repo == null) return@executeOnPooledThread
            upstreamRepo = repo
            val cwDir = getCwDir()
            val cached = AuthorCache.load(repo, cwDir)
            val authors = if (cached.isNotEmpty()) cached
            else GitService.fetchPrAuthors(repo).also { AuthorCache.save(repo, it, cwDir) }
            SwingUtilities.invokeLater {
                allAuthors = authors
                rebuildReviewerBadges()
                applyReviewerSelection(DevConfig.load(cwDir).git.default_reviewers)
            }
        }
    }

    private fun rebuildReviewerBadges() {
        reviewersWrap.removeAll()
        reviewerBadges = allAuthors.map { author -> makeReviewerBadge(author, false) }
        reviewerBadges.forEach { reviewersWrap.add(it) }
        reviewersWrap.revalidate(); reviewersWrap.repaint()
    }

    private fun applyReviewerSelection(selected: List<String>) {
        val selectedSet = selected.toSet()
        reviewerBadges.zip(allAuthors).forEach { (badge, author) ->
            badge.putClientProperty("active", author.login in selectedSet)
            TicketsPanel.applyBadgeStyle(badge)
        }
        refreshReviewersHeader()
    }

    // ── UI state (expand/collapse) persistence ──────────────────────────────────

    private fun uiStateFile(): File? = getCwDir()?.let { File(it, "pr-config-ui-state.json") }

    private fun loadUiStateFromDisk() {
        val f = uiStateFile() ?: return
        if (!f.exists()) return
        try {
            @Suppress("UNCHECKED_CAST")
            val map = Gson().fromJson(f.readText(), Map::class.java) as? Map<String, Any> ?: return
            (map["reviewersExpanded"] as? Boolean)?.let { reviewersExpanded = it }
        } catch (_: Exception) {
        }
    }

    private fun saveUiStateToDisk() {
        val f = uiStateFile() ?: return
        try {
            f.parentFile?.mkdirs()
            f.writeText(Gson().toJson(mapOf("reviewersExpanded" to reviewersExpanded)))
        } catch (_: Exception) {
        }
    }

    // ── Load / Save ───────────────────────────────────────────────────────────

    fun loadConfig() {
        val cfg = DevConfig.load(getCwDir())
        userSessionField.text = cfg.git.user_session
        statusLabel.text = ""

        hasMergePermission.putClientProperty("active", cfg.git.can_merge)
        noMergePermission.putClientProperty("active", !cfg.git.can_merge)
        TicketsPanel.applyBadgeStyle(hasMergePermission)
        TicketsPanel.applyBadgeStyle(noMergePermission)

        if (allAuthors.isNotEmpty()) applyReviewerSelection(cfg.git.default_reviewers)
    }

    private fun saveConfig() {
        val cwDir = getCwDir()
        val selectedReviewers = reviewerBadges.zip(allAuthors)
            .filter { (badge, _) -> badge.getClientProperty("active") == true }
            .map { (_, author) -> author.login }

        val existing = DevConfig.load(cwDir)
        val cfg = existing.copy(
            git = existing.git.copy(
                base_branch = (baseBranchCombo.selectedItem as? String ?: "").trim(),
                default_reviewers = selectedReviewers,
                user_session = String(userSessionField.password),
                can_merge = hasMergePermission.getClientProperty("active") == true
            )
        )
        DevConfig.save(cfg, cwDir)
        statusLabel.text = "  Saved ✓"
        statusLabel.foreground = JBUI.CurrentTheme.Label.foreground()
        Timer(2500) { statusLabel.text = "" }.apply { isRepeats = false; start() }
    }
}
