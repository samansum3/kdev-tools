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

class TicketsPanel(
    private val project: Project,
    private val tabId: String,
    private val onLoadingChanged: (Boolean) -> Unit = {}
) : JPanel(BorderLayout()) {

    private val parentKeysField = JBTextField().apply { toolTipText = "e.g. CW-36000, CW-36001" }
    private val linkedKeysField = JBTextField().apply { toolTipText = "e.g. CW-123, CW-456" }
    private val fixVersionField = JBTextField().apply { toolTipText = "e.g. 13073" }

    // owner badges (mutually exclusive)
    private val badgeMyTasks = makeBadge("My Tasks", false)
    private val badgeUnassigned = makeBadge("Unassigned", false)
    private val badgeHideDone = makeBadge("Hide Done", false)

    // Dynamic badges — rebuilt when meta is loaded
    private var statusBadges: List<JLabel> = emptyList()
    private var notStatusBadges: List<JLabel> = emptyList()
    private var typeBadges: List<JLabel> = emptyList()
    private var notTypeBadges: List<JLabel> = emptyList()
    private var visibleStatuses: List<String> = emptyList()
    private var visibleTypeInfos: List<JiraMetaService.IssueTypeInfo> = emptyList()

    // status name -> Jira statusCategory colorName (e.g. "green", "yellow", "blue-gray"),
    // populated from JiraMetaCache so ticket card badges can use real status colors
    // instead of guessing from the status name.
    private var statusColorByName: Map<String, String> = emptyMap()

    // Wrap containers rebuilt on meta load
    private lateinit var statusWrap: JPanel
    private lateinit var notStatusWrap: JPanel
    private lateinit var typeWrap: JPanel
    private lateinit var notTypeWrap: JPanel
    private lateinit var filtersBody: JPanel
    private lateinit var filterToggleLabel: JLabel

    private val assigneeUIService = AssigneeUIService(this)

    private val clearSearchExtension = com.intellij.ui.components.fields.ExtendableTextComponent.Extension.create(
        com.intellij.icons.AllIcons.Actions.Close,
        com.intellij.icons.AllIcons.Actions.CloseHovered,
        "Clear search"
    ) { clearSearch() }

    private val searchField = com.intellij.ui.components.fields.ExtendableTextField().apply {
        toolTipText = "Search by summary or key..."; emptyText.text = "Search by summary or key..."
    }
    private val searchInfoLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    var allLoadedTickets: List<JiraTicket> = emptyList()
    var currentJql: String = ""
    var filtersExpanded = true
    val cardsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    var currentUserEmail: String? = null

    // Icon memory caches
    // typeName → list of pending callbacks (supports multiple badges per type)
    private val typeIconCallbacks = mutableMapOf<String, MutableList<(ImageIcon) -> Unit>>()
    private val typeIconMemCache = mutableMapOf<String, ImageIcon>()   // typeName → resolved icon

    // priorityName → list of pending callbacks / resolved icon — same disk+mem caching algorithm
    // as type icons, keyed by priority name instead of type name.
    private val priorityIconCallbacks = mutableMapOf<String, MutableList<(ImageIcon) -> Unit>>()
    private val priorityIconMemCache = mutableMapOf<String, ImageIcon>()

    private val transitionsCache = mutableMapOf<String, List<Pair<String, String>>>()
    private var debounceTimer: Timer? = null
    private val DEBOUNCE_MS = 300
    private val requestGeneration = AtomicLong(0)

    private var pendingFilterState: Map<String, Boolean> = emptyMap()

    // ── JiraMetaCache listener ────────────────────────────────────────────────

    // Keep a reference so we can remove it on dispose
    private val metaListener: (JiraMetaCache.State) -> Unit = { state ->
        // Always on EDT (guaranteed by JiraMetaCache)
        statusColorByName = state.statuses.associate { it.name to it.colorName }
        rebuildDynamicBadges(state.statuses.map { it.name }, state.types)
        applyPendingFilterState()
        onFilterBadgeChanged()
        // Status meta (and thus colors) may load after cards were already rendered with the
        // fallback color — re-render so badges pick up the real Jira colors once available.
        if (allLoadedTickets.isNotEmpty()) applySearch()
    }

    init {
        border = JBUI.Borders.empty(8, 10)
        buildUi()
        loadFilterStateFromDisk()
        JiraMetaCache.addListener(metaListener)
        syncFromMetaCacheIfLoaded()
        // Trigger initial load — if already cached, callback fires immediately;
        // if another tab already triggered it, this is a no-op (handled above instead).
        val cw = cwDir()
        if (cw != null) JiraMetaCache.load(cw)
    }

    override fun addNotify() {
        super.addNotify()
        JiraMetaCache.addListener(metaListener)
        // Re-attaching after being hidden (e.g. switching Tickets sub-tabs) may have missed
        // updates that happened while this tab's listener was detached — catch up now.
        syncFromMetaCacheIfLoaded()
    }

    override fun removeNotify() {
        super.removeNotify()
        JiraMetaCache.removeListener(metaListener)
    }

    /**
     * [JiraMetaCache.load] is a no-op once another tab/instance has already populated the cache
     * for this project, so a freshly-built TicketsPanel would otherwise never receive the data
     * it needs to build its status/type filter badges until the *next* cache change. Calling the
     * listener directly with whatever's already cached fixes that without waiting on an event.
     */
    private fun syncFromMetaCacheIfLoaded() {
        val current = JiraMetaCache.current()
        if (current.statuses.isNotEmpty() || current.types.isNotEmpty()) {
            metaListener(current)
        }
    }

    // ── git/cw ────────────────────────────────────────────────────────────────

    fun cwDir(): File? = com.khalibre.link2command.devpanel.common.ProjectPaths.cwDir(project)

    /** Per-tab state dir for this tab's own parent keys / fix version / filters. */
    private fun tabStateDir(): File? = cwDir()?.let { TicketTabsStore.tabStateDir(it, tabId) }

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
        val dir = tabStateDir() ?: return
        val filtersFile = File(dir, "filters.json")
        if (!filtersFile.exists()) return
        try {
            @Suppress("UNCHECKED_CAST")
            val map =
                Gson().fromJson(filtersFile.readText(), Map::class.java) as Map<String, Boolean>
            pendingFilterState = map

            val parentFile = File(dir, "parent-tickets")
            val linkedFile = File(dir, "linked-tickets")
            val versionFile = File(dir, "fix-version")
            val searchFile = File(dir, "search")
            if (parentKeysField.text.isBlank() && parentFile.exists()) {
                val keys = parentFile.readLines().map { it.trim() }
                    .filter { it.matches(Regex("[A-Z]+-[0-9]+")) }
                if (keys.isNotEmpty()) parentKeysField.text = keys.joinToString(", ")
            }
            if (linkedKeysField.text.isBlank() && linkedFile.exists()) {
                val keys = linkedFile.readLines().map { it.trim() }
                    .filter { it.matches(Regex("[A-Z]+-[0-9]+")) }
                if (keys.isNotEmpty()) linkedKeysField.text = keys.joinToString(", ")
            }
            if (fixVersionField.text.isBlank() && versionFile.exists()) {
                val v = versionFile.readText().trim()
                if (v.isNotBlank()) fixVersionField.text = v
            }
            if (searchField.text.isBlank() && searchFile.exists()) {
                val s = searchFile.readText().trim()
                if (s.isNotBlank()) {
                    searchField.text = s
                    updateSearchClearIcon()
                }
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

    private fun currentFilterStateMap(): MutableMap<String, Boolean> {
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
        return map
    }

    private fun saveToGitCw() {
        val dir = tabStateDir() ?: return
        dir.mkdirs()

        val keys = parentKeysField.text.split(",").map { it.trim().uppercase() }
            .filter { it.matches(Regex("[A-Z]+-[0-9]+")) }
        if (keys.isNotEmpty()) File(dir, "parent-tickets").writeText(keys.joinToString("\n"))
        else File(dir, "parent-tickets").delete()

        val linkedKeys = linkedKeysField.text.split(",").map { it.trim().uppercase() }
            .filter { it.matches(Regex("[A-Z]+-[0-9]+")) }
        if (linkedKeys.isNotEmpty()) File(dir, "linked-tickets").writeText(linkedKeys.joinToString("\n"))
        else File(dir, "linked-tickets").delete()

        val ver = fixVersionField.text.trim()
        if (ver.isNotBlank()) File(dir, "fix-version").writeText(ver)
        else File(dir, "fix-version").delete()

        val search = searchField.text.trim()
        if (search.isNotBlank()) File(dir, "search").writeText(search)
        else File(dir, "search").delete()

        val map = currentFilterStateMap()
        try {
            File(dir, "filters.json").writeText(Gson().toJson(map))
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
        val inputRow = JPanel(GridLayout(1, 3, 6, 0)).apply {
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
        inputRow.add(inputBlock("linked to tickets", linkedKeysField))
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
        linkedKeysField.addActionListener { refresh() }
        fixVersionField.addActionListener { refresh() }
        updateSearchClearIcon()
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onSearchTextChanged()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onSearchTextChanged()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = onSearchTextChanged()
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
        pendingFilterState = currentFilterStateMap()
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

    /**
     * [pendingFilterState] used to only ever be populated once, from disk, at construction time.
     * That meant any badge the person toggled *after* that point lived only on the live JLabel's
     * "active" client property — which gets thrown away and rebuilt from scratch every time this
     * tab is detached and reattached (e.g. switching to another sub-tab and back triggers
     * [removeNotify]/[addNotify] → [syncFromMetaCacheIfLoaded] → [rebuildDynamicBadges], which
     * makes brand-new badge labels). Keeping this map continuously in sync with the live badges
     * on every toggle is what lets [applyPendingFilterState] correctly restore them afterwards.
     */
    fun onFilterBadgeChanged() {
        filterToggleLabel.text = filterHeaderText()
        pendingFilterState = currentFilterStateMap()
    }

    // ── Refresh / data loading ────────────────────────────────────────────────

    fun refresh() {
        debounceTimer?.stop()
        debounceTimer = Timer(DEBOUNCE_MS) { doRefresh() }.apply { isRepeats = false; start() }
    }

    private fun onSearchTextChanged() {
        applySearch()
        updateSearchClearIcon()
        saveToGitCw()
    }

    /** Shows the "x" clear icon inside the search box only while it has text. */
    private fun updateSearchClearIcon() {
        val hasText = searchField.text.isNotEmpty()
        val hasExtension = searchField.getExtensions().contains(clearSearchExtension)
        if (hasText && !hasExtension) searchField.addExtension(clearSearchExtension)
        else if (!hasText && hasExtension) searchField.removeExtension(clearSearchExtension)
    }

    /** Clicking the search box's "x": clear the text, clear the saved keyword, and reload data. */
    private fun clearSearch() {
        searchField.text = ""
        updateSearchClearIcon()
        saveToGitCw()
        refresh()
    }

    private fun doRefresh() {
        val filters = buildFilters()
        if (filters.parentKeys.isEmpty() && filters.linkedKeys.isEmpty() && filters.fixVersion.isBlank()) {
            cardsPanel.removeAll()
            cardsPanel.add(JBLabel("<html><i>Enter parent ticket(s), linked ticket(s), or a fix version to load tickets.</i></html>").apply {
                border = JBUI.Borders.empty(16, 4)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })
            cardsPanel.revalidate(); cardsPanel.repaint()
            setStatus(""); return
        }

        saveToGitCw()
        onLoadingChanged(true)
        val myGeneration = requestGeneration.incrementAndGet()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (currentUserEmail == null) currentUserEmail = JiraService.currentUserEmail()
                val jql = JiraService.buildJql(filters, currentUserEmail)
                val tickets = JiraService.searchTickets(jql)
                SwingUtilities.invokeLater {
                    if (requestGeneration.get() != myGeneration) return@invokeLater
                    currentJql = jql
                    allLoadedTickets = tickets
                    applySearch(); setStatus("")
                    onLoadingChanged(false)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    if (requestGeneration.get() != myGeneration) return@invokeLater
                    setStatus("Error: ${e.message?.take(80)}")
                    onLoadingChanged(false)
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

    fun applySearch() {
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
        val linkedKeys = linkedKeysField.text.split(",").map { it.trim().uppercase() }
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
            linkedKeys = linkedKeys,
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
                toolTipText = "Click to copy ${ticket.key}"
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        CardUtils.copyToClipboardWithBalloon(
                            ticket.key, e.component, e.point, "Copied ${ticket.key}"
                        )
                    }
                })
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
        val compactMode = DevConfig.load().ticket.itemMode == "compact"
        val metaPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false; border = JBUI.Borders.emptyTop(1)
        }
        if (compactMode) metaPanel.add(buildStatusDropdownTrigger(ticket))
        else metaPanel.add(makeStatusBadge(ticket.status, ticket.statusColorName))
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
            loadPriorityIconAsync(priorityName, ticket.priorityIconUrl, 14) {
                iconLabel.icon = it; metaPanel.repaint()
            }
            metaPanel.add(iconLabel)
            metaPanel.add(Box.createHorizontalStrut(2))
            metaPanel.add(JBLabel(priorityName).apply {
                font = font.deriveFont(font.size - 2f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })
            metaPanel.add(sepLabel())
        }

        metaPanel.add(assigneeUIService.buildAssigneeCombo(ticket))
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

        // Sync immediately too: if WrapLayout already reports the correct wrapped height on
        // this very first layout pass, componentResized never fires (the size doesn't change),
        // so without this the card would be left hugging its initial (often wrong) bounds.
        // A couple of deferred passes catch the case where the card isn't fully sized/showing yet.
        syncCardHeight(card)
        SwingUtilities.invokeLater {
            syncCardHeight(card)
            SwingUtilities.invokeLater { syncCardHeight(card) }
        }

        return card
    }

    /** Force the card's maximumSize height to match its current preferredSize after layout. */
    private fun syncCardHeight(card: JPanel) {
        val ph = card.preferredSize.height
        if (card.maximumSize.height != ph) {
            card.maximumSize = Dimension(Int.MAX_VALUE, ph)
            card.revalidate()
        }
    }

    private fun sepLabel() = JBLabel("  ·  ").apply {
        font = font.deriveFont(font.size - 2f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private fun addTicketActions(panel: JPanel, ticket: JiraTicket, isMe: Boolean) {
        val isUnassigned = ticket.assigneeName == null
        val devTypes = DevConfig.load().ticket.developmentTypes.toSet()
        val isEligibleType = devTypes.isEmpty() || ticket.issueType in devTypes
        if ((isUnassigned || ticket.status == "Failed QA") && isEligibleType)
            panel.add(CardUtils.makeActionButton("Pick") { doPickTicket(ticket.key) })
        panel.add(CardUtils.makeActionButton("View") { JiraService.openTicketInBrowser(ticket.key) })

        val compactMode = DevConfig.load().ticket.itemMode == "compact"
        if (compactMode) {
            // Compact mode moves transitions into the status-badge dropdown (see
            // buildStatusDropdownTrigger) and frees up the room a row of transition buttons
            // used to take — that space goes to this "Copy link" button instead.
            lateinit var copyLinkBtn: JButton
            copyLinkBtn = CardUtils.makeActionButton("Copy link") {
                CardUtils.copyToClipboardWithBalloon(
                    JiraService.ticketUrl(ticket.key), copyLinkBtn, "Link copied"
                )
            }
            panel.add(copyLinkBtn)
            return
        }

        val cacheKey = transitionsCacheKey(ticket)
        ensureTransitionsLoaded(ticket, cacheKey, onLoading = {
            val loadingLabel = JBLabel("…").apply {
                font = font.deriveFont(font.size - 1f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                border = JBUI.Borders.empty(0, 4)
            }
            panel.add(loadingLabel)
            loadingLabel
        }) { transitions, loadingLabel ->
            loadingLabel?.let { panel.remove(it) }
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

    private fun transitionsCacheKey(ticket: JiraTicket) =
        "${ticket.key}-${ticket.status}-${ticket.assigneeName}"

    /**
     * Cache-or-fetch helper for a ticket's available transitions, shared by the default mode's
     * inline transition buttons and compact mode's status-badge dropdown. [onLoading] is only
     * invoked (synchronously, on the EDT) when a network fetch is actually needed, and should
     * return whatever placeholder it added so [onReady] can clean it up; pass `null` from
     * [onLoading] if there's nothing to clean up.
     */
    private fun <T> ensureTransitionsLoaded(
        ticket: JiraTicket,
        cacheKey: String,
        onLoading: () -> T,
        onReady: (transitions: List<Pair<String, String>>, placeholder: T?) -> Unit
    ) {
        val cached = transitionsCache[cacheKey]
        if (cached != null) {
            onReady(cached, null); return
        }
        val placeholder = onLoading()
        ApplicationManager.getApplication().executeOnPooledThread {
            val transitions = JiraService.fetchAvailableTransitions(ticket.key)
            transitionsCache[cacheKey] = transitions
            SwingUtilities.invokeLater { onReady(transitions, placeholder) }
        }
    }

    private fun renderTransitionButtons(
        panel: JPanel, ticket: JiraTicket, isMe: Boolean, transitions: List<Pair<String, String>>
    ) {
        // Collected so that clicking any one of them immediately disables all of them for this
        // card — prevents double-clicks/extra transitions while the request is in flight.
        // They get naturally reset once the card is rebuilt on a successful refresh; on failure
        // (no refresh happens) we explicitly re-enable them below.
        val transitionButtons = mutableListOf<JButton>()
        transitions.forEach { (targetStatus, transitionName) ->
            val (bg, fg) = resolveStatusColors(targetStatus)
            val btn = CardUtils.makeTransitionButton(targetStatus, transitionName, bg, fg) {
                performTransition(ticket.key, targetStatus, transitionButtons)
            }
            transitionButtons += btn
            panel.add(btn)
        }
    }

    // ── Compact mode: status badge → transitions dropdown ──────────────────────

    /**
     * Compact mode's stand-in for the status badge: same look, plus a caret indicating it opens
     * a dropdown of the ticket's available transitions (built from the exact same
     * [CardUtils.makeTransitionButton] pieces used by default mode's inline buttons — just
     * stacked vertically in a popup instead of laid out in a row).
     */
    private fun buildStatusDropdownTrigger(ticket: JiraTicket): JComponent {
        var currentStatus = ticket.status
        val colorName = statusColorByName[currentStatus]
        val (bg, fg) = colorName?.let { jiraStatusColor(it) } ?: legacyGuessColor(currentStatus)

        val combo = object : com.intellij.openapi.ui.ComboBox<Pair<String, String>>() {
            var isHovered = false
            var paintColor = bg

            override fun getForeground(): Color = fg

            override fun getInsets(): Insets = Insets(0, 0, 0, 0)

            override fun getInsets(insets: Insets): Insets {
                val i = getInsets()
                insets.set(i.top, i.left, i.bottom, i.right)
                return insets
            }

            override fun getPreferredSize(): Dimension {
                @Suppress("UNCHECKED_CAST")
                val r = renderer as? ListCellRenderer<Any?> ?: return super.getPreferredSize()
                val rendererComp =
                    r.getListCellRendererComponent(JList<Any?>(), selectedItem, -1, false, false)
                val content = rendererComp.preferredSize
                val i = getInsets()
                return Dimension(content.width + i.left + i.right, content.height + i.top + i.bottom)
            }

            override fun doLayout() {
                super.doLayout()
                // Zero out the arrow button AFTER the UI delegate's layout manager runs,
                // so its stale bounds never shrink the renderer's display area.
                for (comp in components) {
                    if (comp is JButton) {
                        comp.bounds = Rectangle(0, 0, 0, 0)
                    }
                }
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                )
                if (isHovered) {
                    g2.color = paintColor.darker()
                } else {
                    g2.color = paintColor
                }
                val w = width.toDouble()
                val h = height.toDouble()
                val r = JBUI.scale(4).toDouble()
                g2.fill(java.awt.geom.RoundRectangle2D.Double(0.0, 0.0, w, h, r, r))
                g2.dispose()
                super.paintComponent(g)
            }
        }
        combo.isEditable = false
        combo.isOpaque = false
        combo.paintColor = bg
        combo.foreground = fg
        combo.background = Color(0, 0, 0, 0)
        combo.border = JBUI.Borders.empty()
        combo.font = combo.font.deriveFont(combo.font.size - 2f)
        combo.putClientProperty("JComboBox.isBorderless", true)
        combo.putClientProperty("JComboBox.isTableCellEditor", false)
        combo.toolTipText = "Click to change status"

        for (comp in combo.components) {
            if (comp is JButton) {
                comp.isVisible = false
            }
        }
        combo.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                combo.isHovered = true
                combo.repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                combo.isHovered = false
                combo.repaint()
            }
        })

        // Only the current status is known up front; transitions arrive async below.
        val model = DefaultComboBoxModel<Pair<String, String>>()
        combo.model = model
        combo.selectedItem = null
        combo.renderer = object : ListCellRenderer<Pair<String, String>> {
            override fun getListCellRendererComponent(
                list: JList<out Pair<String, String>>,
                value: Pair<String, String>?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val status = value?.first ?: currentStatus

                if (index == -1) {
                    val caret = if (combo.isEnabled) "▾" else "…"
                    return JLabel("$status  $caret").apply {
                        isOpaque = false
                        font = combo.font
                        border = JBUI.Borders.empty(2, 6)
                        background = Color(0, 0, 0, 0)
                    }
                }

                // Popup row: "→ [status badge]"
                val badge = makeStatusBadge(status)
                return JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
                    isOpaque = isSelected
                    if (isSelected) background = list.selectionBackground
                    add(JLabel("→").apply {
                        foreground = if (isSelected) list.selectionForeground else list.foreground
                        border = JBUI.Borders.emptyRight(8)
                    })
                    add(badge)
                }
            }
        }

        fun setEnabledState(e: Boolean) {
            combo.isEnabled = e
            combo.cursor = Cursor.getPredefinedCursor(if (e) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
            combo.repaint()
        }

        fun rerenderComboToContent() {
            combo.invalidate()   // mark this component's cached size as stale
            combo.revalidate()   // walk up to the nearest validate root and schedule layout
            combo.repaint()
        }

        fun updateComboBackground(status: String) {
            val colorName = statusColorByName[status]
            val (bg, fg) = colorName?.let { jiraStatusColor(it) } ?: legacyGuessColor(status)
            combo.paintColor = bg
            combo.foreground = fg
        }

        fun setCurrentStatus(status: String) {
            currentStatus = status
            ticket.status = status
            updateComboBackground(status)
            rerenderComboToContent()
        }

        combo.addActionListener {
            val selected = combo.selectedItem as? Pair<String, String> ?: return@addActionListener
            val targetStatus = selected.first
            if (targetStatus == currentStatus) return@addActionListener

            val originalStatus = currentStatus

            // Optimistic update: reflect the picked status immediately, and lock the
            // dropdown until the request resolves.
            setCurrentStatus(targetStatus)
            setEnabledState(false)

            ApplicationManager.getApplication().executeOnPooledThread {
                val result = JiraService.transitionTicket(ticket.key, targetStatus)
                if (result.isSuccess) {
                    // Still on background thread — safe to do the network call here
                    val refreshed = try {
                        JiraService.refreshTicket(currentJql, ticket.key)
                    } catch (_: Exception) {
                        null
                    }

                    SwingUtilities.invokeLater {
                        // Card gets fully rebuilt with authoritative data (including a
                        // freshly-fetched transitions list) — nothing left to reset here.
                        if (refreshed == null) {
                            // No longer matches current filters (e.g. an "Unassigned"/"My Tasks" filter
                            // is active and the new assignee no longer satisfies it) — remove it,
                            // leaving every other ticket untouched.
                            allLoadedTickets = allLoadedTickets.filter { it.key != ticket.key }
                        } else {
                            ticket.status = refreshed.status
                            ticket.statusColorName = refreshed.statusColorName
                            ticket.availableTransitions = refreshed.availableTransitions
                        }
                        applySearch()
                        setEnabledState(true)
                    }
                } else {
                    SwingUtilities.invokeLater {
                        setStatus("✗ ${result.exceptionOrNull()?.message?.take(60)}")
                        setCurrentStatus(originalStatus)
                        combo.selectedItem = null
                        setEnabledState(true)
                    }
                }
            }
        }

        setEnabledState(false)
        ensureTransitionsLoaded(ticket, transitionsCacheKey(ticket), onLoading = {
            setEnabledState(false)
        }) { result, _ ->
            val newModel = DefaultComboBoxModel<Pair<String, String>>()
            result?.forEach { pair -> if (pair.first != currentStatus) newModel.addElement(pair) }
            combo.model = newModel
            combo.selectedItem = null
            setEnabledState(true)
        }

        return combo
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

    /**
     * Same caching algorithm as [loadTicketTypeIconAsync] (mem cache → named disk cache →
     * remote fetch, with queued multi-callback support), keyed by priority name instead of type name.
     */
    fun loadPriorityIconAsync(
        priorityName: String,
        url: String?,
        size: Int,
        onLoaded: (ImageIcon) -> Unit
    ) {
        // Already resolved
        priorityIconMemCache[priorityName]?.let { icon ->
            onLoaded(scaleIcon(icon, size)); return
        }

        // Queue the callback; if first caller, kick off the load
        val callbacks = priorityIconCallbacks.getOrPut(priorityName) { mutableListOf() }
        callbacks += onLoaded
        if (callbacks.size > 1) return  // load already in flight

        ApplicationManager.getApplication().executeOnPooledThread {
            val cw = cwDir()
            var img: ImageIcon? = null

            if (cw != null) {
                val cacheFile = JiraMetaService.priorityIconCacheFile(cw, priorityName)
                if (cacheFile.exists()) img = loadAndScaleFile(cacheFile, size)
            }

            if (img == null && !url.isNullOrBlank()) {
                img =
                    CardUtils.fetchRemoteIcon(url, size, project.basePath?.let { File(it) }, false)
                // Persist to named cache file
                if (img != null && cw != null) {
                    try {
                        val cacheFile = JiraMetaService.priorityIconCacheFile(cw, priorityName)
                        cacheFile.parentFile.mkdirs()
                        val raw = ImageIO.read(java.net.URL(url))
                        if (raw != null) ImageIO.write(raw, "png", cacheFile)
                    } catch (_: Exception) {
                    }
                }
            }

            val finalImg = img ?: return@executeOnPooledThread
            priorityIconMemCache[priorityName] = finalImg

            SwingUtilities.invokeLater {
                priorityIconCallbacks.remove(priorityName)?.forEach { cb -> cb(scaleIcon(finalImg, size)) }
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

    /**
     * Disables every transition button on this card immediately (synchronously, before the
     * network call even starts) so a user can't fire off a second transition while the first
     * is still in flight. On success the card gets entirely rebuilt by [refresh] — fresh buttons,
     * naturally re-enabled. On failure no rebuild happens, so we explicitly reset the card's
     * buttons back to enabled here.
     */
    private fun performTransition(key: String, targetStatus: String, cardButtons: List<JButton>) {
        cardButtons.forEach { it.isEnabled = false }
        setStatus("$key → $targetStatus…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = if (targetStatus == "In Progress")
                JiraService.transitionToInProgress(key)
            else
                JiraService.transitionTicket(key, targetStatus)
            SwingUtilities.invokeLater {
                if (result.isSuccess) {
                    setStatus("✓ $key → $targetStatus"); refresh()
                } else {
                    setStatus("✗ ${result.exceptionOrNull()?.message?.take(60)}")
                    cardButtons.forEach { it.isEnabled = true }
                }
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

    fun setStatus(text: String) {
        statusLabel.text = text
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

    /**
     * Builds a status badge whose color reflects Jira's own statusCategory.colorName
     * for that status, rather than guessing from the status name.
     *
     * Resolution order:
     *  1. [statusColorByName] (from JiraMetaCache, keyed by status name) — authoritative
     *     once project meta has loaded.
     *  2. [fallbackColorName] — the colorName carried directly on the ticket's own
     *     status field (from the search response), used before/independently of meta load.
     *  3. A small set of legacy name-based guesses, kept only as a last-resort fallback
     *     for the rare case neither of the above is available (e.g. offline/error state).
     *  4. Neutral gray.
     */
    private fun makeStatusBadge(status: String, fallbackColorName: String? = null): JLabel {
        val colorName = statusColorByName[status] ?: fallbackColorName
        val (bg, fg) = colorName?.let { jiraStatusColor(it) } ?: legacyGuessColor(status)
        return object : JLabel(status) {
            // BasicComboBoxUI.paintCurrentValue() forcibly overwrites a renderer component's
            // background/foreground with the combo's own colors right before painting the
            // collapsed value. Overriding the getters keeps the real badge colors intact no
            // matter what external code (including Swing internals) tries to set them to.
            override fun getBackground(): Color = bg
            override fun getForeground(): Color = fg

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = background
                val w = width.toDouble()
                val h = height.toDouble()
                val r = JBUI.scale(4).toDouble()
                g2.fill(java.awt.geom.RoundRectangle2D.Double(0.0, 0.0, w, h, r, r))
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            font = font.deriveFont(font.size - 2f)
            border = JBUI.Borders.empty(2, 6)
        }
    }

    private fun resolveStatusColors(status: String): Pair<Color, Color> =
        statusColorByName[status]?.let { jiraStatusColor(it) } ?: legacyGuessColor(status)

    /** Maps Jira's statusCategory.colorName values to a (background, foreground) badge pair. */
    private fun jiraStatusColor(colorName: String): Pair<Color, Color> = when (colorName) {
        "green" -> Pair(Color(234, 243, 222), Color(59, 109, 17))
        "yellow" -> Pair(Color(250, 238, 218), Color(133, 79, 11))
        "blue", "blue-gray" -> Pair(Color(230, 241, 251), Color(24, 95, 165))
        "red", "warm-red" -> Pair(Color(252, 235, 235), Color(163, 45, 45))
        "brown" -> Pair(Color(243, 234, 224), Color(120, 84, 40))
        "purple" -> Pair(Color(242, 235, 250), Color(101, 60, 163))
        "medium-gray", "gray" -> Pair(Color(241, 239, 232), Color(95, 94, 90))
        else -> Pair(Color(241, 239, 232), Color(95, 94, 90))
    }

    /** Last-resort fallback when no Jira-provided color is available at all. */
    private fun legacyGuessColor(status: String): Pair<Color, Color> = when (status) {
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
}
