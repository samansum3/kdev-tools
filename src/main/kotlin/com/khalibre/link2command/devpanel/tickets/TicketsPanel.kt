package com.khalibre.link2command.devpanel.tickets

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.common.CardUtils
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*

class TicketsPanel(private val project: Project? = null) : JPanel(BorderLayout()) {

    private val parentKeysField = JBTextField().apply { toolTipText = "e.g. CW-36000, CW-36001" }
    private val fixVersionField = JBTextField().apply { toolTipText = "e.g. 13073" }

    // owner (mutually exclusive — toggling one deactivates the other)
    private val badgeMyTasks =
        makeBadge("My Tasks", false).also { it.putClientProperty("group", "owner") }
    private val badgeUnassigned =
        makeBadge("Unassigned", false).also { it.putClientProperty("group", "owner") }

    // status badges
    private val badgeToDo = makeBadge("To Do", false)
    private val badgeReadyForDev = makeBadge("Ready for Dev", false)
    private val badgeInProgress = makeBadge("In Progress", false)
    private val badgePrOpen = makeBadge("PR Open", false)
    private val badgeMerged = makeBadge("Merged", false)
    private val badgeDeployedUat = makeBadge("Deployed to UAT", false)
    private val badgePendingQa = makeBadge("Pending QA", false)
    private val badgeFailedQa = makeBadge("Failed QA", false)
    private val badgeDone = makeBadge("Done", false)
    private val badgeHideDone = makeBadge("Hide Done", false)

    private val typeNames = listOf(
        "Story", "Epic", "Improvement", "Task", "Sub-task",
        "Bug", "Defect", "Operations", "Test Report", "Release Procedure", "Translation Update"
    )
    private val typeBadges = typeNames.map { makeBadge(it, false) }
    private val notTypeBadges = typeNames.map { makeBadge(it, false) }

    // All filter badges in one flat list — used for active-count computation
    private val allFilterBadges: List<JLabel>
        get() = listOf(
            badgeMyTasks, badgeUnassigned,
            badgeToDo, badgeReadyForDev, badgeInProgress, badgePrOpen, badgeMerged,
            badgeDeployedUat, badgePendingQa, badgeFailedQa, badgeDone, badgeHideDone
        ) + typeBadges + notTypeBadges

    // Collapse state
    private var filtersExpanded = true
    private lateinit var filterToggleLabel: JLabel
    private lateinit var filtersBody: JPanel

    private val cardsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private var currentUserEmail: String? = null
    private val iconCache = mutableMapOf<String, ImageIcon?>()

    init {
        border = JBUI.Borders.empty(8, 10)
        buildUi()
        loadFromGitCw()
    }

    // ── .git/cw persistence ──────────────────────────────────────────────────

    private fun gitRoot(): File? {
        val base = project?.basePath ?: return null
        var dir = File(base)
        while (dir.parentFile != null) {
            if (File(dir, ".git").isDirectory) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun cwDir(): File? = gitRoot()?.let { File(it, ".git/cw") }

    private fun loadFromGitCw() {
        val cw = cwDir() ?: return
        val parentFile = File(cw, "parent-tickets")
        val versionFile = File(cw, "fix-version")

        if (parentKeysField.text.isBlank() && parentFile.exists()) {
            val keys = parentFile.readLines()
                .map { it.trim() }
                .filter { it.matches(Regex("[A-Z]+-[0-9]+")) }
            if (keys.isNotEmpty()) parentKeysField.text = keys.joinToString(", ")
        }

        if (fixVersionField.text.isBlank() && versionFile.exists()) {
            val v = versionFile.readText().trim()
            if (v.isNotBlank()) fixVersionField.text = v
        }
    }

    private fun saveToGitCw() {
        val cw = cwDir() ?: return
        cw.mkdirs()

        val keys = parentKeysField.text.split(",")
            .map { it.trim().uppercase() }
            .filter { it.matches(Regex("[A-Z]+-[0-9]+")) }
        if (keys.isNotEmpty()) {
            File(cw, "parent-tickets").writeText(keys.joinToString("\n"))
        }

        val ver = fixVersionField.text.trim()
        if (ver.isNotBlank()) {
            File(cw, "fix-version").writeText(ver)
        }
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private fun buildUi() {
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    requestFocusInWindow()
                }
            })
        }

        // ── Input row ────────────────────────────────────────────────────────
        val inputRow = JPanel(GridLayout(1, 2, 6, 0)).apply {
            isOpaque = false; maximumSize = Dimension(Int.MAX_VALUE, 54)
        }

        fun inputBlock(label: String, field: JTextField): JPanel {
            val p = JPanel(BorderLayout(0, 2)).apply { isOpaque = false }
            p.add(
                JBLabel(label).apply { font = font.deriveFont(font.size - 1f) },
                BorderLayout.NORTH
            )
            p.add(field, BorderLayout.CENTER)
            return p
        }
        inputRow.add(inputBlock("parent tickets", parentKeysField))
        inputRow.add(inputBlock("fix version", fixVersionField))
        topPanel.add(inputRow)
        topPanel.add(Box.createVerticalStrut(8))

        // ── Collapsible filter block ─────────────────────────────────────────
        topPanel.add(buildCollapsibleFilters())
        topPanel.add(Box.createVerticalStrut(4))
        topPanel.add(statusLabel)
        topPanel.add(Box.createVerticalStrut(4))

        add(topPanel, BorderLayout.NORTH)

        val scroll = JBScrollPane(cardsPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    requestFocusInWindow()
                }
            })
        }
        add(scroll, BorderLayout.CENTER)

        parentKeysField.addActionListener { refresh() }
        fixVersionField.addActionListener { refresh() }
    }

    /**
     * Builds the collapsible "Filters" section.
     * Header row: "Filters (N)  ▾" — clicking anywhere on it toggles the body.
     * Body: the four filter subsections (owner, status, type, not type).
     */
    private fun buildCollapsibleFilters(): JPanel {
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
        }

        // ── Header ───────────────────────────────────────────────────────────
        filterToggleLabel = JLabel(filterHeaderText()).apply {
            font = font.deriveFont(Font.BOLD, font.size.toFloat())
            foreground = JBUI.CurrentTheme.Label.foreground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(2, 0)
        }

        val headerRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, filterToggleLabel.preferredSize.height + 6)
            add(filterToggleLabel)
        }

        // Toggle on click anywhere in headerRow
        val toggleListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = toggleFilters()
            override fun mouseEntered(e: MouseEvent) {
                filterToggleLabel.foreground = Color(24, 95, 165)
            }

            override fun mouseExited(e: MouseEvent) {
                filterToggleLabel.foreground = JBUI.CurrentTheme.Label.foreground()
            }
        }
        filterToggleLabel.addMouseListener(toggleListener)
        headerRow.addMouseListener(toggleListener)

        container.add(headerRow)
        container.add(Box.createVerticalStrut(4))

        // ── Body (the four sections) ─────────────────────────────────────────
        filtersBody = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
        }
        filtersBody.add(filterSection("owner", listOf(badgeMyTasks, badgeUnassigned)))
        filtersBody.add(Box.createVerticalStrut(4))
        filtersBody.add(
            filterSection(
                "status",
                listOf(
                    badgeToDo, badgeReadyForDev, badgeInProgress, badgePrOpen, badgeMerged,
                    badgeDeployedUat, badgePendingQa, badgeFailedQa, badgeDone, badgeHideDone
                )
            )
        )
        filtersBody.add(Box.createVerticalStrut(4))
        filtersBody.add(filterSection("type", typeBadges))
        filtersBody.add(Box.createVerticalStrut(4))
        filtersBody.add(filterSection("not type", notTypeBadges))

        container.add(filtersBody)
        return container
    }

    private fun toggleFilters() {
        filtersExpanded = !filtersExpanded
        filtersBody.isVisible = filtersExpanded
        filterToggleLabel.text = filterHeaderText()
        // Force the top panel to recompute its preferred size
        filtersBody.revalidate()
        parent?.revalidate()
        parent?.repaint()
    }

    /** "Filters (N)  ▾" or "Filters (N)  ▸" depending on expand state. */
    private fun filterHeaderText(): String {
        val count = allFilterBadges.count { it.getClientProperty("active") == true }
        val countStr = if (count > 0) " ($count)" else ""
        val caret = if (filtersExpanded) "▾" else "▸"
        return "Filters$countStr  $caret"
    }

    /** Called by badge click handlers so the header count stays in sync. */
    fun onFilterBadgeChanged() {
        filterToggleLabel.text = filterHeaderText()
    }

    private fun filterSection(label: String, badges: List<JLabel>): JPanel {
        val p = JPanel(BorderLayout(0, 2)).apply { isOpaque = false }
        p.add(JBLabel(label).apply {
            font = font.deriveFont(font.size - 2f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }, BorderLayout.NORTH)
        val wrap = JPanel(WrapLayout(FlowLayout.LEFT, 4, 3)).apply { isOpaque = false }
        badges.forEach { wrap.add(it) }
        p.add(wrap, BorderLayout.CENTER)
        return p
    }

    // ── Refresh / data loading ────────────────────────────────────────────────

    fun refresh() {
        val filters = buildFilters()

        if (filters.parentKeys.isEmpty() && filters.fixVersion.isBlank()) {
            SwingUtilities.invokeLater {
                cardsPanel.removeAll()
                cardsPanel.add(JBLabel("<html><i>Enter parent ticket(s) or a fix version to load tickets.</i></html>").apply {
                    border = JBUI.Borders.empty(16, 4)
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                })
                cardsPanel.revalidate(); cardsPanel.repaint()
                setStatus("")
            }
            return
        }

        saveToGitCw()
        setStatus("Loading…")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (currentUserEmail == null) currentUserEmail = JiraService.currentUserEmail()
                val jql = JiraService.buildJql(filters, currentUserEmail)
                val tickets = JiraService.searchTickets(jql)
                SwingUtilities.invokeLater {
                    cardsPanel.removeAll()
                    if (tickets.isEmpty()) {
                        cardsPanel.add(JBLabel("No tickets found").apply {
                            border = JBUI.Borders.empty(16, 4)
                            foreground = JBUI.CurrentTheme.Label.disabledForeground()
                        })
                    } else {
                        cardsPanel.add(JBLabel("${tickets.size} ticket(s)").apply {
                            border = JBUI.Borders.emptyBottom(6)
                            font = font.deriveFont(font.size - 1f)
                            foreground = JBUI.CurrentTheme.Label.disabledForeground()
                        })
                        tickets.forEach { ticket ->
                            cardsPanel.add(buildTicketCard(ticket))
                            cardsPanel.add(Box.createVerticalStrut(6))
                        }
                    }
                    cardsPanel.revalidate(); cardsPanel.repaint(); setStatus("")
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setStatus("Error: ${e.message?.take(80)}")
                    cardsPanel.removeAll()
                    cardsPanel.add(JBLabel("<html>${CardUtils.escHtml(e.message ?: "Unknown error")}</html>").apply {
                        border = JBUI.Borders.empty(12, 4)
                        foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    })
                    cardsPanel.revalidate(); cardsPanel.repaint()
                }
            }
        }
    }

    private fun buildFilters(): TicketFilters {
        val parentKeys = parentKeysField.text.split(",").map { it.trim().uppercase() }
            .filter { it.matches(Regex("[A-Z]+-[0-9]+")) }

        val activeTypes = typeBadges.zip(typeNames)
            .filter { (badge, _) -> badge.getClientProperty("active") == true }
            .map { (_, name) -> name }.toSet()

        val activeNotTypes = notTypeBadges.zip(typeNames)
            .filter { (badge, _) -> badge.getClientProperty("active") == true }
            .map { (_, name) -> name }.toSet()

        fun active(b: JLabel) = b.getClientProperty("active") == true
        return TicketFilters(
            parentKeys = parentKeys,
            fixVersion = fixVersionField.text.trim(),
            myTasks = active(badgeMyTasks),
            unassigned = active(badgeUnassigned),
            filterToDo = active(badgeToDo),
            filterReadyForDev = active(badgeReadyForDev),
            filterInProgress = active(badgeInProgress),
            filterPrOpen = active(badgePrOpen),
            filterMerged = active(badgeMerged),
            filterDeployedUat = active(badgeDeployedUat),
            filterPendingQa = active(badgePendingQa),
            filterFailedQa = active(badgeFailedQa),
            filterDone = active(badgeDone),
            hideDone = active(badgeHideDone),
            typeFilter = activeTypes,
            notTypeFilter = activeNotTypes
        )
    }

    // ── Card builder ─────────────────────────────────────────────────────────

    private fun buildTicketCard(ticket: JiraTicket): JPanel {
        val card = CardUtils.makeCard()
        val gbc = CardUtils.cardGbc()

        // ── Row 0: key + truncating summary ──────────────────────────────────
        val titlePanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val g = GridBagConstraints()
            g.gridx = 0; g.gridy = 0; g.weightx = 0.0; g.fill = GridBagConstraints.NONE
            g.insets = Insets(0, 0, 0, 4)
            add(JBLabel(ticket.key).apply {
                font = Font(Font.MONOSPACED, Font.BOLD, font.size - 1)
                foreground = Color(24, 95, 165)
            }, g)
            g.gridx = 1; g.weightx = 1.0; g.fill = GridBagConstraints.HORIZONTAL
            g.insets = Insets(0, 0, 0, 0)
            add(JBLabel(ticket.summary).apply {
                font = font.deriveFont(Font.BOLD)
                minimumSize = Dimension(0, preferredSize.height)
            }, g)
        }
        gbc.gridy = 0; card.add(titlePanel, gbc)

        // ── Row 1: status badge · type · priority · assignee ─────────────────
        val isMe = ticket.assigneeEmail != null && ticket.assigneeEmail == currentUserEmail
        val metaPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false; border = JBUI.Borders.emptyTop(1)
        }

        metaPanel.add(makeStatusBadge(ticket.status))
        metaPanel.add(Box.createHorizontalStrut(6))

        ticket.issueType?.let { typeName ->
            val iconLabel =
                JLabel().apply { preferredSize = Dimension(14, 14); toolTipText = typeName }
            loadIconAsync(ticket.issueTypeIconUrl, 14) { iconLabel.icon = it; metaPanel.repaint() }
            metaPanel.add(iconLabel)
            metaPanel.add(Box.createHorizontalStrut(2))
            metaPanel.add(JBLabel(typeName).apply {
                font = font.deriveFont(font.size - 2f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })
            metaPanel.add(sepLabel())
        }

        ticket.priority?.let { priorityName ->
            val iconLabel =
                JLabel().apply { preferredSize = Dimension(14, 14); toolTipText = priorityName }
            loadIconAsync(ticket.priorityIconUrl, 14) { iconLabel.icon = it; metaPanel.repaint() }
            metaPanel.add(iconLabel)
            metaPanel.add(Box.createHorizontalStrut(2))
            metaPanel.add(JBLabel(priorityName).apply {
                font = font.deriveFont(font.size - 2f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })
            metaPanel.add(sepLabel())
        }

        val assigneeText = when {
            ticket.assigneeName == null -> "unassigned"
            isMe -> "● ${ticket.assigneeName}"
            else -> ticket.assigneeName
        }
        metaPanel.add(JBLabel(assigneeText).apply {
            font = font.deriveFont(font.size - 2f)
            foreground =
                if (isMe) Color(59, 109, 17) else JBUI.CurrentTheme.Label.disabledForeground()
        })
        gbc.gridy = 1; card.add(metaPanel, gbc)

        // ── Row 2: action buttons ─────────────────────────────────────────────
        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false; border = JBUI.Borders.emptyLeft(-5)
        }
        addTicketActions(actionPanel, ticket, isMe)
        gbc.gridy = 2; card.add(actionPanel, gbc)

        card.addHierarchyListener {
            card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        }
        return card
    }

    private fun sepLabel() = JBLabel("  ·  ").apply {
        font = font.deriveFont(font.size - 2f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private fun addTicketActions(panel: JPanel, ticket: JiraTicket, isMe: Boolean) {
        val status = ticket.status
        val isUnassigned = ticket.assigneeName == null
        if (isUnassigned || status == "Failed QA")
            panel.add(CardUtils.makeActionButton("pick") { runInTerminal("pick-ticket ${ticket.key}") })
        if (isMe && status in listOf("Deployed to UAT", "Pending QA", "Failed QA", "Merged"))
            panel.add(CardUtils.makeTransitionButton("in progress") { doTransitionInProgress(ticket.key) })
        if (isMe && status == "In Progress")
            panel.add(CardUtils.makeTransitionButton("PR Open") {
                doTransition(
                    ticket.key,
                    "PR Open"
                )
            })
        if (isMe && status == "PR Open")
            panel.add(CardUtils.makeTransitionButton("Merged") {
                doTransition(
                    ticket.key,
                    "Merged"
                )
            })
        if (status == "Merged")
            panel.add(CardUtils.makeTransitionButton("Deployed UAT") {
                doTransition(
                    ticket.key,
                    "Deployed to UAT"
                )
            })
        if (status == "Deployed to UAT")
            panel.add(CardUtils.makeTransitionButton("Pending QA") {
                doTransition(
                    ticket.key,
                    "Pending QA"
                )
            })
        panel.add(CardUtils.makeActionButton("view") { JiraService.openTicketInBrowser(ticket.key) })
    }

    private fun runInTerminal(command: String) {
        val proj = project ?: return
        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(proj)
                .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID) ?: return@invokeLater
            toolWindow.activate {
                val selectedContent = toolWindow.contentManager.selectedContent ?: return@activate
                val tabInfo = TerminalToolWindowTabsManager.getInstance(proj).tabs
                    .firstOrNull { it.content == selectedContent } ?: return@activate
                tabInfo.view.component.requestFocusInWindow()
                tabInfo.view.createSendTextBuilder().shouldExecute().send(command)
            }
        }
    }

    // ── Transition helpers ────────────────────────────────────────────────────

    private fun doTransition(key: String, targetStatus: String) {
        setStatus("$key → $targetStatus…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = JiraService.transitionTicket(key, targetStatus)
            SwingUtilities.invokeLater {
                if (result.isSuccess) {
                    setStatus("✓ $key → $targetStatus"); refresh()
                } else setStatus("✗ ${result.exceptionOrNull()?.message?.take(60)}")
            }
        }
    }

    private fun doTransitionInProgress(key: String) {
        setStatus("$key → In Progress…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = JiraService.transitionToInProgress(key)
            SwingUtilities.invokeLater {
                if (result.isSuccess) {
                    setStatus("✓ $key → In Progress"); refresh()
                } else setStatus("✗ ${result.exceptionOrNull()?.message?.take(60)}")
            }
        }
    }

    private fun setStatus(text: String) {
        statusLabel.text = text
    }

    private fun loadIconAsync(url: String?, size: Int, onLoaded: (ImageIcon) -> Unit) {
        if (url.isNullOrBlank()) return
        val cached = iconCache[url]
        if (cached != null) {
            onLoaded(cached); return
        }
        if (iconCache.containsKey(url)) return
        iconCache[url] = null
        ApplicationManager.getApplication().executeOnPooledThread {
            val img = CardUtils.fetchRemoteIcon(url, size)
            if (img != null) {
                iconCache[url] = img
                SwingUtilities.invokeLater { onLoaded(img) }
            }
        }
    }

    fun deactivateGroupExcept(group: String, except: JLabel) {
        listOf(badgeMyTasks, badgeUnassigned)
            .filter { it != except && it.getClientProperty("group") == group }
            .forEach {
                it.putClientProperty("active", false)
                TicketsPanel.applyBadgeStyle(it)
                it.repaint()
            }
    }

    // ── Badge factory (companion) ─────────────────────────────────────────────

    companion object {
        fun makeBadge(text: String, initiallyActive: Boolean): JLabel {
            val label = JLabel(text)
            label.font = label.font.deriveFont(label.font.size - 2f)
            label.isOpaque = true
            label.putClientProperty("active", initiallyActive)
            label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            applyBadgeStyle(label)
            label.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val nowActive = label.getClientProperty("active") != true
                    label.putClientProperty("active", nowActive)
                    applyBadgeStyle(label); label.repaint()
                    val group = label.getClientProperty("group") as? String
                    if (nowActive && group != null) {
                        var p = label.parent
                        while (p != null) {
                            if (p is TicketsPanel) {
                                p.deactivateGroupExcept(group, label); break
                            }
                            p = p.parent
                        }
                    }
                    // Update header count, then refresh
                    var p = label.parent
                    while (p != null) {
                        if (p is TicketsPanel) {
                            p.onFilterBadgeChanged()
                            p.refresh()
                            break
                        }
                        p = p.parent
                    }
                }

                override fun mouseEntered(e: MouseEvent) {
                    label.putClientProperty("hovered", true); applyBadgeStyle(label)
                }

                override fun mouseExited(e: MouseEvent) {
                    label.putClientProperty("hovered", false); applyBadgeStyle(label)
                }
            })
            return label
        }

        internal fun applyBadgeStyle(label: JLabel) {
            val active = label.getClientProperty("active") == true
            val hovered = label.getClientProperty("hovered") == true
            when {
                active -> {
                    label.background = Color(24, 95, 165)
                    label.foreground = Color.WHITE
                    label.border = JBUI.Borders.empty(3, 8)
                }

                hovered -> {
                    label.background = Color(210, 228, 248)
                    label.foreground = Color(24, 95, 165)
                    label.border = JBUI.Borders.empty(3, 8)
                }

                else -> {
                    label.background = Color(225, 223, 218)
                    label.foreground = Color(110, 108, 103)
                    label.border = JBUI.Borders.empty(3, 8)
                }
            }
        }
    }

    private fun makeStatusBadge(status: String): JLabel {
        val (bg, fg) = when (status) {
            "In Progress", "Defining AC", "PR Open" ->
                Pair(Color(230, 241, 251), Color(24, 95, 165))

            "Done", "Closed", "Resolved", "Merged", "Deployed to UAT", "Pending QA" ->
                Pair(Color(234, 243, 222), Color(59, 109, 17))

            "Blocked", "Failed QA" ->
                Pair(Color(252, 235, 235), Color(163, 45, 45))

            "Submitted for Review", "Pending AC Review" ->
                Pair(Color(250, 238, 218), Color(133, 79, 11))

            else ->
                Pair(Color(241, 239, 232), Color(95, 94, 90))
        }
        return JLabel(status).apply {
            isOpaque = true; background = bg; foreground = fg
            font = font.deriveFont(font.size - 2f); border = JBUI.Borders.empty(2, 6)
        }
    }
}
