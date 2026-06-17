package com.khalibre.link2command.devpanel.tickets

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.common.CardUtils
import com.khalibre.link2command.devpanel.config.DevConfig
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.*

class TicketsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val parentKeysField = JBTextField().apply { toolTipText = "e.g. CW-36000, CW-36001" }
    private val fixVersionField = JBTextField().apply { toolTipText = "e.g. 13073" }

    // owner badges (mutually exclusive)
    private val badgeMyTasks =
        makeBadge("My Tasks", false).also { it.putClientProperty("group", "owner") }
    private val badgeUnassigned =
        makeBadge("Unassigned", false).also { it.putClientProperty("group", "owner") }
    private val badgeHideDone = makeBadge("Hide Done", false)

    // Dynamic badges — rebuilt when meta is loaded
    private var statusBadges: List<JLabel> = emptyList()
    private var notStatusBadges: List<JLabel> = emptyList()
    private var typeBadges: List<JLabel> = emptyList()
    private var notTypeBadges: List<JLabel> = emptyList()
    private var visibleStatuses: List<String> = emptyList()
    private var visibleTypeInfos: List<JiraMetaService.IssueTypeInfo> = emptyList()

    // Wrap containers rebuilt on meta load
    private lateinit var statusWrap: JPanel
    private lateinit var notStatusWrap: JPanel
    private lateinit var typeWrap: JPanel
    private lateinit var notTypeWrap: JPanel
    private lateinit var filtersBody: JPanel
    private lateinit var filterToggleLabel: JLabel

    private val searchField = JBTextField().apply {
        toolTipText = "Search by summary or key..."; emptyText.text = "Search by summary or key..."
    }
    private val searchInfoLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private var allLoadedTickets: List<JiraTicket> = emptyList()
    private var filtersExpanded = true
    private val cardsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private var currentUserEmail: String? = null

    // Icon memory caches
    // typeName → list of pending callbacks (supports multiple badges per type)
    private val typeIconCallbacks = mutableMapOf<String, MutableList<(ImageIcon) -> Unit>>()
    private val typeIconMemCache = mutableMapOf<String, ImageIcon>()   // typeName → resolved icon
    private val memIconCache = mutableMapOf<String, ImageIcon?>()  // url → icon (priority etc.)

    private val transitionsCache = mutableMapOf<String, List<Pair<String, String>>>()
    private var debounceTimer: Timer? = null
    private val DEBOUNCE_MS = 300
    private val requestGeneration = AtomicLong(0)

    private var pendingFilterState: Map<String, Boolean> = emptyMap()

    // ── JiraMetaCache listener ────────────────────────────────────────────────

    // Keep a reference so we can remove it on dispose
    private val metaListener: (JiraMetaCache.State) -> Unit = { state ->
        // Always on EDT (guaranteed by JiraMetaCache)
        rebuildDynamicBadges(state.statuses, state.types)
        applyPendingFilterState()
        onFilterBadgeChanged()
    }

    init {
        border = JBUI.Borders.empty(8, 10)
        buildUi()
        loadFilterStateFromDisk()
        JiraMetaCache.addListener(metaListener)
        // Trigger initial load — if already cached, callback fires immediately
        val cw = cwDir()
        if (cw != null) JiraMetaCache.load(cw)
    }

    override fun removeNotify() {
        super.removeNotify()
        JiraMetaCache.removeListener(metaListener)
    }

    // ── git/cw ────────────────────────────────────────────────────────────────

    private fun gitRoot(): File? {
        val base = project.basePath ?: return null
        var dir = File(base)
        while (dir.parentFile != null) {
            if (File(dir, ".git").isDirectory) return dir
            dir = dir.parentFile
        }
        return null
    }

    fun cwDir(): File? = gitRoot()?.let { File(it, ".git/cw") }

    // ── Dynamic badges ────────────────────────────────────────────────────────

    private fun rebuildDynamicBadges(
        allStatuses: List<String>,
        allTypes: List<JiraMetaService.IssueTypeInfo>
    ) {
        // Always re-read config so excluded lists reflect latest saved Ticket Config
        val cfg = DevConfig.load().ticket
        val excludedStatuses = cfg.excludedStatuses.toSet()
        val excludedTypes = cfg.excludedTypes.toSet()

        visibleStatuses = allStatuses.filter { it !in excludedStatuses }
        visibleTypeInfos = allTypes.filter { it.name !in excludedTypes }

        statusBadges = visibleStatuses.map { name -> makeBadge(name, false) }
        notStatusBadges = visibleStatuses.map { name -> makeBadge(name, false) }
        typeBadges = visibleTypeInfos.map { info -> makeTypeBadge(info) }
        notTypeBadges = visibleTypeInfos.map { info -> makeTypeBadge(info) }

        statusWrap.removeAll()
        statusBadges.forEach { statusWrap.add(it) }
        statusWrap.add(badgeHideDone)

        notStatusWrap.removeAll()
        notStatusBadges.forEach { notStatusWrap.add(it) }

        typeWrap.removeAll()
        typeBadges.forEach { typeWrap.add(it) }

        notTypeWrap.removeAll()
        notTypeBadges.forEach { notTypeWrap.add(it) }

        statusWrap.revalidate(); statusWrap.repaint()
        notStatusWrap.revalidate(); notStatusWrap.repaint()
        typeWrap.revalidate(); typeWrap.repaint()
        notTypeWrap.revalidate(); notTypeWrap.repaint()
        filtersBody.revalidate(); filtersBody.repaint()
    }

    /**
     * Badge with a small type icon on the left.
     * Uses multi-callback icon loading so both typeBadges and notTypeBadges
     * for the same type both receive the icon independently.
     */
    private fun makeTypeBadge(info: JiraMetaService.IssueTypeInfo): JLabel {
        val badge = makeBadge(info.name, false)
        loadTicketTypeIconAsync(info.name, info.iconUrl, 12) { icon ->
            badge.icon = icon
            badge.iconTextGap = 3
            badge.revalidate(); badge.repaint()
        }
        return badge
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun loadFilterStateFromDisk() {
        val cw = cwDir() ?: return
        val filtersFile = File(cw, "filters.json")
        if (!filtersFile.exists()) return
        try {
            @Suppress("UNCHECKED_CAST")
            val map =
                Gson().fromJson(filtersFile.readText(), Map::class.java) as Map<String, Boolean>
            pendingFilterState = map

            val parentFile = File(cw, "parent-tickets")
            val versionFile = File(cw, "fix-version")
            if (parentKeysField.text.isBlank() && parentFile.exists()) {
                val keys = parentFile.readLines().map { it.trim() }
                    .filter { it.matches(Regex("[A-Z]+-[0-9]+")) }
                if (keys.isNotEmpty()) parentKeysField.text = keys.joinToString(", ")
            }
            if (fixVersionField.text.isBlank() && versionFile.exists()) {
                val v = versionFile.readText().trim()
                if (v.isNotBlank()) fixVersionField.text = v
            }
            filtersExpanded = map["filtersExpanded"] ?: true
            filtersBody.isVisible = filtersExpanded
        } catch (_: Exception) {
        }
    }

    private fun applyPendingFilterState() {
        val map = pendingFilterState
        if (map.isEmpty()) return
        fun restore(badge: JLabel, key: String) {
            badge.putClientProperty("active", map[key] ?: false)
            applyBadgeStyle(badge)
        }
        restore(badgeMyTasks, "myTasks")
        restore(badgeUnassigned, "unassigned")
        restore(badgeHideDone, "hideDone")
        statusBadges.zip(visibleStatuses).forEach { (b, n) -> restore(b, "status_$n") }
        notStatusBadges.zip(visibleStatuses).forEach { (b, n) -> restore(b, "notStatus_$n") }
        typeBadges.zip(visibleTypeInfos).forEach { (b, i) -> restore(b, "type_${i.name}") }
        notTypeBadges.zip(visibleTypeInfos).forEach { (b, i) -> restore(b, "notType_${i.name}") }
    }

    private fun saveToGitCw() {
        val cw = cwDir() ?: return
        cw.mkdirs()

        val keys = parentKeysField.text.split(",").map { it.trim().uppercase() }
            .filter { it.matches(Regex("[A-Z]+-[0-9]+")) }
        if (keys.isNotEmpty()) File(cw, "parent-tickets").writeText(keys.joinToString("\n"))
        else File(cw, "parent-tickets").delete()

        val ver = fixVersionField.text.trim()
        if (ver.isNotBlank()) File(cw, "fix-version").writeText(ver)
        else File(cw, "fix-version").delete()

        fun active(b: JLabel) = b.getClientProperty("active") == true
        val map = mutableMapOf(
            "myTasks" to active(badgeMyTasks),
            "unassigned" to active(badgeUnassigned),
            "hideDone" to active(badgeHideDone)
        )
        statusBadges.zip(visibleStatuses).forEach { (b, n) -> map["status_$n"] = active(b) }
        notStatusBadges.zip(visibleStatuses).forEach { (b, n) -> map["notStatus_$n"] = active(b) }
        typeBadges.zip(visibleTypeInfos).forEach { (b, i) -> map["type_${i.name}"] = active(b) }
        notTypeBadges.zip(visibleTypeInfos)
            .forEach { (b, i) -> map["notType_${i.name}"] = active(b) }
        map["filtersExpanded"] = filtersExpanded
        try {
            File(cw, "filters.json").writeText(Gson().toJson(map))
        } catch (_: Exception) {
        }
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private fun buildUi() {
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = requestFocusInWindow().let {}
            })
        }

        // Input row
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

        // Search row
        val searchRow = JPanel(GridLayout(1, 2, 6, 0)).apply {
            isOpaque = false; maximumSize = Dimension(Int.MAX_VALUE, 28)
        }
        searchRow.add(searchField)
        searchRow.add(searchInfoLabel)
        topPanel.add(searchRow)
        topPanel.add(Box.createVerticalStrut(6))

        // Badge wrap panels (empty until meta loaded)
        statusWrap = JPanel(WrapLayout(FlowLayout.LEFT, 4, 3)).apply { isOpaque = false }
        notStatusWrap = JPanel(WrapLayout(FlowLayout.LEFT, 4, 3)).apply { isOpaque = false }
        typeWrap = JPanel(WrapLayout(FlowLayout.LEFT, 4, 3)).apply { isOpaque = false }
        notTypeWrap = JPanel(WrapLayout(FlowLayout.LEFT, 4, 3)).apply { isOpaque = false }

        topPanel.add(buildCollapsibleFilters())
        topPanel.add(Box.createVerticalStrut(4))
        topPanel.add(statusLabel)
        topPanel.add(Box.createVerticalStrut(4))

        add(topPanel, BorderLayout.NORTH)

        val scroll = JBScrollPane(cardsPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)

        parentKeysField.addActionListener { refresh() }
        fixVersionField.addActionListener { refresh() }
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = applySearch()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = applySearch()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = applySearch()
        })

        loadFilterStateFromDisk()
    }

    private fun buildCollapsibleFilters(): JPanel {
        val container =
            JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }

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

        filtersBody =
            JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
        filtersBody.add(
            filterSection(
                "owner",
                listOf(badgeMyTasks, badgeUnassigned),
                prebuiltWrap = null
            )
        )
        filtersBody.add(Box.createVerticalStrut(4))
        filtersBody.add(filterSection("status", emptyList(), prebuiltWrap = statusWrap))
        filtersBody.add(Box.createVerticalStrut(4))
        filtersBody.add(filterSection("not status", emptyList(), prebuiltWrap = notStatusWrap))
        filtersBody.add(Box.createVerticalStrut(4))
        filtersBody.add(filterSection("type", emptyList(), prebuiltWrap = typeWrap))
        filtersBody.add(Box.createVerticalStrut(4))
        filtersBody.add(filterSection("not type", emptyList(), prebuiltWrap = notTypeWrap))
        container.add(filtersBody)
        return container
    }

    private fun filterSection(label: String, badges: List<JLabel>, prebuiltWrap: JPanel?): JPanel {
        val p = JPanel(BorderLayout(0, 2)).apply { isOpaque = false }
        p.add(JBLabel(label).apply {
            font = font.deriveFont(font.size - 2f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }, BorderLayout.NORTH)
        val wrap = prebuiltWrap ?: JPanel(WrapLayout(FlowLayout.LEFT, 4, 3)).apply {
            isOpaque = false; badges.forEach { add(it) }
        }
        p.add(wrap, BorderLayout.CENTER)
        return p
    }

    private fun toggleFilters() {
        filtersExpanded = !filtersExpanded
        filtersBody.isVisible = filtersExpanded
        filterToggleLabel.text = filterHeaderText()
        saveToGitCw()
        filtersBody.revalidate(); parent?.revalidate(); parent?.repaint()
    }

    private fun filterHeaderText(): String {
        val count = allFilterBadges().count { it.getClientProperty("active") == true }
        val countStr = if (count > 0) " ($count)" else ""
        val caret = if (filtersExpanded) "▾" else "▸"
        return "Filters$countStr  $caret"
    }

    private fun allFilterBadges(): List<JLabel> =
        listOf(
            badgeMyTasks,
            badgeUnassigned,
            badgeHideDone
        ) + statusBadges + notStatusBadges + typeBadges + notTypeBadges

    fun onFilterBadgeChanged() {
        filterToggleLabel.text = filterHeaderText()
    }

    // ── Refresh / data loading ────────────────────────────────────────────────

    fun refresh() {
        debounceTimer?.stop()
        debounceTimer = Timer(DEBOUNCE_MS) { doRefresh() }.apply { isRepeats = false; start() }
    }

    private fun doRefresh() {
        val filters = buildFilters()
        if (filters.parentKeys.isEmpty() && filters.fixVersion.isBlank()) {
            cardsPanel.removeAll()
            cardsPanel.add(JBLabel("<html><i>Enter parent ticket(s) or a fix version to load tickets.</i></html>").apply {
                border = JBUI.Borders.empty(16, 4)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })
            cardsPanel.revalidate(); cardsPanel.repaint()
            setStatus(""); return
        }

        saveToGitCw()
        setStatus("Loading…")
        val myGeneration = requestGeneration.incrementAndGet()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (currentUserEmail == null) currentUserEmail = JiraService.currentUserEmail()
                val jql = JiraService.buildJql(filters, currentUserEmail)
                val tickets = JiraService.searchTickets(jql)
                SwingUtilities.invokeLater {
                    if (requestGeneration.get() != myGeneration) return@invokeLater
                    allLoadedTickets = tickets
                    applySearch(); setStatus("")
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    if (requestGeneration.get() != myGeneration) return@invokeLater
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

    private fun applySearch() {
        val query = searchField.text.trim().lowercase()
        val filtered = if (query.isBlank()) allLoadedTickets
        else allLoadedTickets.filter {
            it.summary.lowercase().contains(query) || it.key.lowercase().contains(query)
        }

        val total = allLoadedTickets.size
        val matched = filtered.size
        searchInfoLabel.text = when {
            query.isBlank() && total == 0 -> ""
            query.isBlank() -> "$total ticket${if (total == 1) "" else "s"}"
            matched == 0 -> "no match in $total"
            matched == total -> "$total ticket${if (total == 1) "" else "s"}"
            else -> "$matched of $total"
        }

        cardsPanel.removeAll()
        if (filtered.isEmpty()) {
            cardsPanel.add(
                JBLabel(
                    if (query.isBlank()) "No tickets found" else "No tickets match $query"
                ).apply {
                    border = JBUI.Borders.empty(16, 4)
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                })
        } else {
            filtered.forEach { ticket ->
                cardsPanel.add(buildTicketCard(ticket))
                cardsPanel.add(Box.createVerticalStrut(6))
            }
        }
        cardsPanel.revalidate(); cardsPanel.repaint()
    }

    private fun buildFilters(): TicketFilters {
        val parentKeys = parentKeysField.text.split(",").map { it.trim().uppercase() }
            .filter { it.matches(Regex("[A-Z]+-[0-9]+")) }
        val activeStatuses = statusBadges.zip(visibleStatuses)
            .filter { (b, _) -> b.getClientProperty("active") == true }
            .map { (_, n) -> n }.toSet()
        val activeNotStatuses = notStatusBadges.zip(visibleStatuses)
            .filter { (b, _) -> b.getClientProperty("active") == true }
            .map { (_, n) -> n }.toSet()
        val activeTypes = typeBadges.zip(visibleTypeInfos)
            .filter { (b, _) -> b.getClientProperty("active") == true }
            .map { (_, i) -> i.name }.toSet()
        val activeNotTypes = notTypeBadges.zip(visibleTypeInfos)
            .filter { (b, _) -> b.getClientProperty("active") == true }
            .map { (_, i) -> i.name }.toSet()

        fun active(b: JLabel) = b.getClientProperty("active") == true
        return TicketFilters(
            parentKeys = parentKeys,
            fixVersion = fixVersionField.text.trim(),
            myTasks = active(badgeMyTasks),
            unassigned = active(badgeUnassigned),
            activeStatuses = activeStatuses,
            notStatusFilter = activeNotStatuses,
            hideDone = active(badgeHideDone),
            typeFilter = activeTypes,
            notTypeFilter = activeNotTypes
        )
    }

    // ── Card builder ──────────────────────────────────────────────────────────

    private fun buildTicketCard(ticket: JiraTicket): JPanel {
        val card = CardUtils.makeCard(ticket.key, "tickets")
        val gbc = CardUtils.cardGbc()

        // Row 0: key + summary
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

        // Row 1: status · type · priority · assignee
        val isMe = ticket.assigneeEmail != null && ticket.assigneeEmail == currentUserEmail
        val metaPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false; border = JBUI.Borders.emptyTop(1)
        }
        metaPanel.add(makeStatusBadge(ticket.status))
        metaPanel.add(Box.createHorizontalStrut(6))

        ticket.issueType?.let { typeName ->
            val iconLabel =
                JLabel().apply { preferredSize = Dimension(14, 14); toolTipText = typeName }
            loadTicketTypeIconAsync(typeName, ticket.issueTypeIconUrl, 14) {
                iconLabel.icon = it; metaPanel.repaint()
            }
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

        // Row 2: action buttons — WrapLayout so they wrap when panel is narrow
        // We use a wrapper panel whose height auto-fits via a ComponentListener on the card.
        val actionPanel = JPanel(WrapLayout(FlowLayout.LEFT, 2, 2)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(-4)
        }
        addTicketActions(actionPanel, ticket, isMe)
        gbc.gridy = 2; card.add(actionPanel, gbc)

        // Keep card max-height in sync whenever the actionPanel reflows
        actionPanel.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = syncCardHeight(card)
        })
        card.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = syncCardHeight(card)
        })

        return card
    }

    /** Force the card's maximumSize height to match its current preferredSize after layout. */
    private fun syncCardHeight(card: JPanel) {
        val ph = card.preferredSize.height
        if (card.maximumSize.height != ph) {
            card.maximumSize = Dimension(Int.MAX_VALUE, ph)
        }
    }

    private fun sepLabel() = JBLabel("  ·  ").apply {
        font = font.deriveFont(font.size - 2f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private fun addTicketActions(panel: JPanel, ticket: JiraTicket, isMe: Boolean) {
        val isUnassigned = ticket.assigneeName == null
        if (isUnassigned || ticket.status == "Failed QA")
            panel.add(CardUtils.makeActionButton("Pick") { doPickTicket(ticket.key) })
        panel.add(CardUtils.makeActionButton("View") { JiraService.openTicketInBrowser(ticket.key) })

        val cacheKey = "${ticket.key}-${ticket.status}-${ticket.assigneeName}"
        val cached = transitionsCache[cacheKey]
        if (cached != null) {
            renderTransitionButtons(panel, ticket, isMe, cached)
        } else {
            val loadingLabel = JBLabel("…").apply {
                font = font.deriveFont(font.size - 1f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                border = JBUI.Borders.empty(0, 4)
            }
            panel.add(loadingLabel)
            ApplicationManager.getApplication().executeOnPooledThread {
                val transitions = JiraService.fetchAvailableTransitions(ticket.key)
                transitionsCache[cacheKey] = transitions
                SwingUtilities.invokeLater {
                    panel.remove(loadingLabel)
                    renderTransitionButtons(panel, ticket, isMe, transitions)
                    panel.revalidate(); panel.repaint()
                    // Trigger height sync up the hierarchy
                    var p: Container? = panel.parent
                    while (p != null) {
                        if (p is JPanel && p.layout is GridBagLayout) {
                            syncCardHeight(p); break
                        }
                        p = p.parent
                    }
                }
            }
        }
    }

    private fun renderTransitionButtons(
        panel: JPanel, ticket: JiraTicket, isMe: Boolean, transitions: List<Pair<String, String>>
    ) {
        transitions.forEach { (targetStatus, transitionName) ->
            val btn = if (targetStatus == "In Progress")
                CardUtils.makeTransitionButton(
                    targetStatus,
                    transitionName
                ) { doTransitionInProgress(ticket.key) }
            else
                CardUtils.makeTransitionButton(
                    targetStatus,
                    transitionName
                ) { doTransition(ticket.key, targetStatus) }
            panel.add(btn)
        }
    }

    // ── Icon loading ──────────────────────────────────────────────────────────

    /**
     * Multi-callback icon loader keyed by type name → <typeName>.png disk cache.
     * Multiple callers for the same typeName all get notified when the icon resolves.
     */
    fun loadTicketTypeIconAsync(
        typeName: String,
        url: String?,
        size: Int,
        onLoaded: (ImageIcon) -> Unit
    ) {
        // Already resolved
        typeIconMemCache[typeName]?.let { icon ->
            // Re-scale if needed (different size requests)
            onLoaded(scaleIcon(icon, size)); return
        }

        // Queue the callback; if first caller, kick off the load
        val callbacks = typeIconCallbacks.getOrPut(typeName) { mutableListOf() }
        callbacks += onLoaded
        if (callbacks.size > 1) return  // load already in flight

        ApplicationManager.getApplication().executeOnPooledThread {
            val cw = cwDir()
            var img: ImageIcon? = null

            if (cw != null) {
                val cacheFile = JiraMetaService.typeIconCacheFile(cw, typeName)
                if (cacheFile.exists()) img = loadAndScaleFile(cacheFile, size)
            }

            if (img == null && !url.isNullOrBlank()) {
                img =
                    CardUtils.fetchRemoteIcon(url, size, project.basePath?.let { File(it) }, false)
                // Persist to named cache file
                if (img != null && cw != null) {
                    try {
                        val cacheFile = JiraMetaService.typeIconCacheFile(cw, typeName)
                        cacheFile.parentFile.mkdirs()
                        val raw = ImageIO.read(java.net.URL(url))
                        if (raw != null) ImageIO.write(raw, "png", cacheFile)
                    } catch (_: Exception) {
                    }
                }
            }

            val finalImg = img ?: return@executeOnPooledThread
            typeIconMemCache[typeName] = finalImg

            SwingUtilities.invokeLater {
                typeIconCallbacks.remove(typeName)?.forEach { cb -> cb(scaleIcon(finalImg, size)) }
            }
        }
    }

    private fun scaleIcon(icon: ImageIcon, size: Int): ImageIcon {
        if (icon.iconWidth == size && icon.iconHeight == size) return icon
        val buf =
            java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = buf.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g.drawImage(icon.image.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null)
        g.dispose()
        return ImageIcon(buf)
    }

    private fun loadIconAsync(url: String?, size: Int, onLoaded: (ImageIcon) -> Unit) {
        if (url.isNullOrBlank()) return
        memIconCache[url]?.let { onLoaded(it); return }
        if (memIconCache.containsKey(url)) return
        memIconCache[url] = null
        ApplicationManager.getApplication().executeOnPooledThread {
            val img = CardUtils.fetchRemoteIcon(url, size, project.basePath?.let { File(it) }, true)
            if (img != null) {
                memIconCache[url] = img
                SwingUtilities.invokeLater { onLoaded(img) }
            }
        }
    }

    private fun loadAndScaleFile(file: File, size: Int): ImageIcon? {
        return try {
            val raw = ImageIO.read(file) ?: return null
            val buf =
                java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = buf.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.drawImage(raw.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null)
            g.dispose()
            ImageIcon(buf)
        } catch (_: Exception) {
            null
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

    private fun doPickTicket(key: String) {
        setStatus("$key: creating branch & transitioning…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = JiraService.pickTicket(project, key)
            SwingUtilities.invokeLater {
                if (result.isSuccess) {
                    setStatus("✓ $key: branch created, In Progress"); refresh()
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

    // ── Group badge deactivation ──────────────────────────────────────────────

    fun deactivateGroupExcept(group: String, except: JLabel) {
        listOf(badgeMyTasks, badgeUnassigned)
            .filter { it != except && it.getClientProperty("group") == group }
            .forEach { it.putClientProperty("active", false); applyBadgeStyle(it); it.repaint() }
    }

    // ── Badge factory ─────────────────────────────────────────────────────────

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
                    var p = label.parent
                    while (p != null) {
                        if (p is TicketsPanel) {
                            p.onFilterBadgeChanged(); p.refresh(); break
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
                    label.background = Color(24, 95, 165); label.foreground =
                        Color.WHITE; label.border = JBUI.Borders.empty(3, 8)
                }

                hovered -> {
                    label.background = Color(210, 228, 248); label.foreground =
                        Color(24, 95, 165); label.border = JBUI.Borders.empty(3, 8)
                }

                else -> {
                    label.background = Color(225, 223, 218); label.foreground =
                        Color(110, 108, 103); label.border = JBUI.Borders.empty(3, 8)
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
            font = font.deriveFont(font.size - 2f)
            border = JBUI.Borders.empty(2, 6)
        }
    }
}
