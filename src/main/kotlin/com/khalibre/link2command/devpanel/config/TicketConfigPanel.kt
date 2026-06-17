package com.khalibre.link2command.devpanel.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.tickets.JiraMetaCache
import com.khalibre.link2command.devpanel.tickets.JiraMetaService
import com.khalibre.link2command.devpanel.tickets.TicketsPanel
import com.khalibre.link2command.devpanel.tickets.WrapLayout
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*

/**
 * "Ticket Config" sub-tab.
 *
 * Layout (top to bottom, all left-aligned):
 *
 *  Per-type collapsible done-status sections:
 *    [icon] <Type> done statuses (N) ▸/▾          ← header row, collapsed by default
 *      [badge1] [badge2] …                         ← badge wrap, hidden when collapsed
 *
 *  Excluded statuses  (hidden from Status filter badges)
 *    [badge1] [badge2] …
 *
 *  Excluded types  (hidden from type / not-type filter badges)
 *    [badge1] [badge2] …
 *
 *  ACTIONS
 *    [Reload ticket statuses]  [Reload ticket types]   <status text>
 *
 *  [Save Ticket Config]
 */
class TicketConfigPanel(
    private val getTicketsPanel: () -> TicketsPanel?,
    private val getCwDir: () -> File?
) : JPanel(BorderLayout()) {

    private var allStatuses: List<String> = emptyList()
    private var allTypes: List<JiraMetaService.IssueTypeInfo> = emptyList()

    // Per-type done-status badge maps  (typeName → list of JLabel badges)
    private val doneStatusBadges = mutableMapOf<String, List<JLabel>>()

    // Excluded badge lists
    private var excludedStatusBadges: List<JLabel> = emptyList()
    private var excludedTypeBadges: List<JLabel> = emptyList()

    // Containers rebuilt when meta changes
    private val doneStatusesContainer =
        JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    private val excludedStatusWrap =
        JPanel(WrapLayout(FlowLayout.LEFT, 4, 3)).apply { isOpaque = false }
    private val excludedTypeWrap =
        JPanel(WrapLayout(FlowLayout.LEFT, 4, 3)).apply { isOpaque = false }

    // Header rows for the two collapsed excluded sections (stored so we can refresh their count labels)
    private lateinit var excludedStatusHeader: JPanel
    private lateinit var excludedTypeHeader: JPanel

    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        alignmentX = LEFT_ALIGNMENT
    }

    init {
        border = JBUI.Borders.empty(10, 12)
        buildUi()
        loadMetaAndConfig()
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private fun buildUi() {
        val form = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }

        // ── Per-type done-status sections (rebuilt by rebuildDoneStatusSections) ──
        form.add(doneStatusesContainer)
        form.add(Box.createVerticalStrut(12))

        // ── Excluded statuses ────────────────────────────────────────────────
        form.add(
            buildCollapsibleExcludedSection(
                label = "Excluded statuses",
                hint = "hidden from Status filter badges",
                wrapPanel = excludedStatusWrap,
                getBadges = { excludedStatusBadges }
            ).also { excludedStatusHeader = it })
        form.add(Box.createVerticalStrut(12))

        // ── Excluded types ───────────────────────────────────────────────────
        form.add(
            buildCollapsibleExcludedSection(
                label = "Excluded types",
                hint = "hidden from type / not-type filter badges",
                wrapPanel = excludedTypeWrap,
                getBadges = { excludedTypeBadges }
            ).also { excludedTypeHeader = it })
        form.add(Box.createVerticalStrut(20))

        // ── ACTIONS ──────────────────────────────────────────────────────────
        form.add(sectionLabel("ACTIONS"))
        form.add(Box.createVerticalStrut(8))

        val actionsRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false; alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 32)
        }
        actionsRow.add(JButton("Reload ticket statuses").apply { addActionListener { reloadStatuses() } })
        actionsRow.add(JButton("Reload ticket types").apply { addActionListener { reloadTypes() } })
        form.add(actionsRow)
        form.add(Box.createVerticalStrut(6))
        form.add(statusLabel)
        form.add(Box.createVerticalStrut(16))

        // ── Save ─────────────────────────────────────────────────────────────
        val saveBtn = JButton("Save Ticket Config").apply { addActionListener { saveConfig() } }
        form.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false; alignmentX = LEFT_ALIGNMENT; add(saveBtn)
        })
        form.add(Box.createVerticalGlue())

        val scroll = JBScrollPane(form).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)
    }

    // ── Meta + config loading ─────────────────────────────────────────────────

    fun loadMetaAndConfig() {
        setStatus("Loading…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val cw = getCwDir()
            val statuses = if (cw != null) JiraMetaService.loadStatuses(cw) else emptyList()
            val types = if (cw != null) JiraMetaService.loadTypes(cw) else emptyList()
            SwingUtilities.invokeLater {
                allStatuses = statuses
                allTypes = types
                rebuildAll()
                applyConfig(DevConfig.load().ticket)
                setStatus(
                    if (statuses.isEmpty() && types.isEmpty())
                        "No cached data — configure Jira credentials and click Reload." else ""
                )
            }
        }
    }

    private fun rebuildAll() {
        rebuildDoneStatusSections()
        rebuildExcludedBadges()
    }

    // ── Per-type done-status collapsible sections ─────────────────────────────

    private fun rebuildDoneStatusSections() {
        doneStatusesContainer.removeAll()
        doneStatusBadges.clear()

        if (allTypes.isEmpty()) {
            doneStatusesContainer.add(JBLabel("<html><i>No type data cached — click \"Reload ticket types\".</i></html>").apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                border = JBUI.Borders.empty(4, 0)
                alignmentX = LEFT_ALIGNMENT
            })
        } else {
            allTypes.forEach { typeInfo ->
                doneStatusesContainer.add(buildDoneStatusSection(typeInfo))
                doneStatusesContainer.add(Box.createVerticalStrut(6))
            }
        }
        doneStatusesContainer.revalidate()
        doneStatusesContainer.repaint()
    }

    private fun buildDoneStatusSection(typeInfo: JiraMetaService.IssueTypeInfo): JPanel {
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; alignmentX =
            LEFT_ALIGNMENT
        }

        // Badge wrap (starts hidden — collapsed by default)
        val badgeWrap = JPanel(WrapLayout(FlowLayout.LEFT, 4, 3)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            isVisible = false
        }
        val badges = allStatuses.map { status -> makeConfigBadge(status, false) }
        doneStatusBadges[typeInfo.name] = badges
        badges.forEach { badgeWrap.add(it) }

        // Header row
        val headerLabel = buildCollapsibleHeader(
            typeName = typeInfo.name,
            iconUrl = typeInfo.iconUrl,
            badgeWrap = badgeWrap,
            getBadges = { doneStatusBadges[typeInfo.name] ?: emptyList() }
        )

        // Wire badge clicks to refresh header count
        badges.forEach { badge ->
            badge.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // Toggle handled by makeConfigBadge; refresh header after
                    SwingUtilities.invokeLater {
                        refreshDoneStatusHeader(
                            headerLabel,
                            typeInfo.name
                        )
                    }
                }
            })
        }

        container.add(headerLabel)
        container.add(badgeWrap)
        return container
    }

    /**
     * Builds a header row: [type icon?] <Type> done statuses (N) ▸/▾
     * Clicking toggles the badgeWrap visibility.
     */
    private fun buildCollapsibleHeader(
        typeName: String,
        iconUrl: String,
        badgeWrap: JPanel,
        getBadges: () -> List<JLabel>
    ): JPanel {
        var expanded = false

        val iconLabel = JLabel().apply { preferredSize = Dimension(14, 14) }
        loadTypeIcon(typeName, iconUrl, 14) { iconLabel.icon = it; iconLabel.repaint() }

        val textLabel = JLabel(headerText(typeName, getBadges(), expanded)).apply {
            font = font.deriveFont(font.size - 1f)
            foreground = JBUI.CurrentTheme.Label.foreground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 24)
            add(iconLabel)
            add(textLabel)
        }

        val clickHandler = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                expanded = !expanded
                badgeWrap.isVisible = expanded
                textLabel.text = headerText(typeName, getBadges(), expanded)
                badgeWrap.revalidate(); badgeWrap.repaint()
                row.parent?.revalidate(); row.parent?.repaint()
            }

            override fun mouseEntered(e: MouseEvent) {
                textLabel.foreground = Color(24, 95, 165)
            }

            override fun mouseExited(e: MouseEvent) {
                textLabel.foreground = JBUI.CurrentTheme.Label.foreground()
            }
        }
        row.addMouseListener(clickHandler)
        textLabel.addMouseListener(clickHandler)

        // Tag the row so we can find and refresh its text label later
        row.putClientProperty("typeName", typeName)
        row.putClientProperty("textLabel", textLabel)
        row.putClientProperty("expanded", expanded)
        row.putClientProperty("getBadges", getBadges)

        return row
    }

    /** Refreshes the header label text (selected count) without toggling expansion. */
    private fun refreshDoneStatusHeader(headerRow: JPanel, typeName: String) {
        val textLabel = headerRow.getClientProperty("textLabel") as? JLabel ?: return

        @Suppress("UNCHECKED_CAST")
        val getBadges = headerRow.getClientProperty("getBadges") as? () -> List<JLabel> ?: return
        val expanded = headerRow.getClientProperty("expanded") as? Boolean ?: false
        textLabel.text = headerText(typeName, getBadges(), expanded)
    }

    private fun headerText(typeName: String, badges: List<JLabel>, expanded: Boolean): String {
        val selected = badges.count { it.getClientProperty("active") == true }
        val count = if (selected > 0) " ($selected)" else ""
        val caret = if (expanded) "▾" else "▸"
        return "$typeName done statuses$count  $caret"
    }

    // ── Excluded collapsible sections ─────────────────────────────────────────

    /**
     * Returns a container holding a collapsible header + badge wrap.
     * The header shows:  "<Label>  (N)  ▸/▾"  and clicking toggles the wrap.
     * Collapsed by default.
     */
    private fun buildCollapsibleExcludedSection(
        label: String,
        hint: String,
        wrapPanel: JPanel,
        getBadges: () -> List<JLabel>
    ): JPanel {
        var expanded = false
        wrapPanel.isVisible = false
        wrapPanel.alignmentX = LEFT_ALIGNMENT

        fun headerText(): String {
            val count = getBadges().count { it.getClientProperty("active") == true }
            val cnt = if (count > 0) "  ($count)" else ""
            val caret = if (expanded) "▾" else "▸"
            return "$label$cnt  $caret"
        }

        val textLabel = JLabel(headerText()).apply {
            font = font.deriveFont(font.size - 1f)
            foreground = JBUI.CurrentTheme.Label.foreground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = hint
        }

        val headerRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 24)
            add(textLabel)
        }

        val clickHandler = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                expanded = !expanded
                wrapPanel.isVisible = expanded
                textLabel.text = headerText()
                wrapPanel.revalidate(); wrapPanel.repaint()
                headerRow.parent?.revalidate(); headerRow.parent?.repaint()
            }

            override fun mouseEntered(e: MouseEvent) {
                textLabel.foreground = Color(24, 95, 165)
            }

            override fun mouseExited(e: MouseEvent) {
                textLabel.foreground = JBUI.CurrentTheme.Label.foreground()
            }
        }
        headerRow.addMouseListener(clickHandler)
        textLabel.addMouseListener(clickHandler)

        // Tag the row so refreshExcludedHeader can update the text
        headerRow.putClientProperty("textLabel", textLabel)
        headerRow.putClientProperty("expanded", { expanded })
        headerRow.putClientProperty("getBadges", getBadges)
        headerRow.putClientProperty("labelText", label)

        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(headerRow)
            add(wrapPanel)
        }
        return container
    }

    /** Refreshes the count text in an excluded-section header row. */
    private fun refreshExcludedHeader(section: JPanel) {
        val inner = (0 until section.componentCount)
            .mapNotNull { section.getComponent(it) as? JPanel }
            .firstOrNull { it.getClientProperty("textLabel") != null } ?: return
        val textLabel = inner.getClientProperty("textLabel") as? JLabel ?: return

        @Suppress("UNCHECKED_CAST")
        val getBadges = inner.getClientProperty("getBadges") as? () -> List<JLabel> ?: return

        @Suppress("UNCHECKED_CAST")
        val isExpanded = (inner.getClientProperty("expanded") as? () -> Boolean)?.invoke() ?: false
        val labelText = inner.getClientProperty("labelText") as? String ?: ""
        val count = getBadges().count { it.getClientProperty("active") == true }
        val cnt = if (count > 0) "  ($count)" else ""
        val caret = if (isExpanded) "▾" else "▸"
        textLabel.text = "$labelText$cnt  $caret"
    }

    // ── Excluded badge sections ───────────────────────────────────────────────

    private fun rebuildExcludedBadges() {
        excludedStatusBadges = allStatuses.map { status ->
            makeConfigBadge(status, false).also { badge ->
                badge.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        SwingUtilities.invokeLater { refreshExcludedHeader(excludedStatusHeader) }
                    }
                })
            }
        }
        excludedStatusWrap.removeAll()
        excludedStatusBadges.forEach { excludedStatusWrap.add(it) }
        excludedStatusWrap.revalidate(); excludedStatusWrap.repaint()

        excludedTypeBadges = allTypes.map { info ->
            makeConfigBadge(info.name, false).also { badge ->
                loadTypeIcon(info.name, info.iconUrl, 12) { icon ->
                    badge.icon = icon; badge.iconTextGap = 3
                    badge.revalidate(); badge.repaint()
                }
                badge.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        SwingUtilities.invokeLater { refreshExcludedHeader(excludedTypeHeader) }
                    }
                })
            }
        }
        excludedTypeWrap.removeAll()
        excludedTypeBadges.forEach { excludedTypeWrap.add(it) }
        excludedTypeWrap.revalidate(); excludedTypeWrap.repaint()
    }

    // ── Apply saved config ────────────────────────────────────────────────────

    private fun applyConfig(cfg: TicketConfig) {
        // Per-type done statuses
        doneStatusBadges.forEach { (typeName, badges) ->
            val saved = cfg.doneStatusesByType[typeName]?.toSet() ?: emptySet()
            badges.zip(allStatuses).forEach { (badge, status) ->
                badge.putClientProperty("active", status in saved)
                TicketsPanel.applyBadgeStyle(badge)
            }
        }
        // Excluded statuses
        val excStatuses = cfg.excludedStatuses.toSet()
        excludedStatusBadges.zip(allStatuses).forEach { (badge, status) ->
            badge.putClientProperty("active", status in excStatuses)
            TicketsPanel.applyBadgeStyle(badge)
        }
        // Excluded types
        val excTypes = cfg.excludedTypes.toSet()
        excludedTypeBadges.zip(allTypes).forEach { (badge, info) ->
            badge.putClientProperty("active", info.name in excTypes)
            TicketsPanel.applyBadgeStyle(badge)
        }
        // Refresh all done-status header labels to show correct counts
        refreshAllDoneHeaders()
        // Refresh excluded section header counts
        if (::excludedStatusHeader.isInitialized) refreshExcludedHeader(excludedStatusHeader)
        if (::excludedTypeHeader.isInitialized) refreshExcludedHeader(excludedTypeHeader)
    }

    private fun refreshAllDoneHeaders() {
        for (i in 0 until doneStatusesContainer.componentCount) {
            val section = doneStatusesContainer.getComponent(i) as? JPanel ?: continue
            for (j in 0 until section.componentCount) {
                val child = section.getComponent(j) as? JPanel ?: continue
                val typeName = child.getClientProperty("typeName") as? String ?: continue
                refreshDoneStatusHeader(child, typeName)
            }
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun saveConfig() {
        fun activeBadgeNames(badges: List<JLabel>, names: List<String>): List<String> =
            badges.zip(names).filter { (b, _) -> b.getClientProperty("active") == true }
                .map { (_, n) -> n }

        val doneByType = doneStatusBadges.mapValues { (_, badges) ->
            activeBadgeNames(badges, allStatuses)
        }
        val excludedStatuses = activeBadgeNames(excludedStatusBadges, allStatuses)
        val excludedTypes = activeBadgeNames(excludedTypeBadges, allTypes.map { it.name })

        val existing = DevConfig.load()
        DevConfig.save(
            existing.copy(
                ticket = TicketConfig(
                    doneStatusesByType = doneByType,
                    excludedStatuses = excludedStatuses,
                    excludedTypes = excludedTypes
                )
            )
        )
        setStatus("Saved ✓")
        Timer(2500) { setStatus("") }.apply { isRepeats = false; start() }
        val cw = getCwDir()
        if (cw != null) {
            JiraMetaCache.notifyConfigChanged(cw)
        }
    }

    // ── Reload actions ────────────────────────────────────────────────────────

    private fun reloadStatuses() {
        setStatus("Reloading statuses…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val cw = getCwDir()
            if (cw != null) {
                JiraMetaService.statusCacheFile(cw).delete()
                val statuses = JiraMetaService.fetchAndCacheStatuses(cw)
                SwingUtilities.invokeLater {
                    allStatuses = statuses
                    rebuildAll()
                    applyConfig(DevConfig.load().ticket)
                    setStatus(if (statuses.isEmpty()) "No statuses returned — check Jira credentials." else "Statuses reloaded ✓")
                    Timer(3000) { setStatus("") }.apply { isRepeats = false; start() }
                    JiraMetaCache.notifyConfigChanged(cw)
                }
            } else SwingUtilities.invokeLater { setStatus("No git repo found.") }
        }
    }

    private fun reloadTypes() {
        setStatus("Reloading types…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val cw = getCwDir()
            if (cw != null) {
                JiraMetaService.typesCacheFile(cw).delete()
                val types = JiraMetaService.fetchAndCacheTypes(cw)
                SwingUtilities.invokeLater {
                    allTypes = types
                    rebuildAll()
                    applyConfig(DevConfig.load().ticket)
                    setStatus(if (types.isEmpty()) "No types returned — check Jira credentials." else "Types reloaded ✓")
                    Timer(3000) { setStatus("") }.apply { isRepeats = false; start() }
                    JiraMetaCache.notifyConfigChanged(cw)
                }
            } else SwingUtilities.invokeLater { setStatus("No git repo found.") }
        }
    }

    // ── Icon loading (reuses TicketsPanel's cache via the panel reference) ────

    /**
     * Loads a type icon. Delegates to TicketsPanel's cache if available,
     * otherwise falls back to a direct async load.
     */
    private fun loadTypeIcon(
        typeName: String,
        iconUrl: String,
        size: Int,
        onLoaded: (ImageIcon) -> Unit
    ) {
        val tp = getTicketsPanel()
        if (tp != null) {
            tp.loadTicketTypeIconAsync(typeName, iconUrl, size, onLoaded)
        } else {
            // Fallback: load directly
            ApplicationManager.getApplication().executeOnPooledThread {
                val cw = getCwDir()
                val cacheFile = cw?.let { JiraMetaService.typeIconCacheFile(it, typeName) }
                var img: ImageIcon? = null
                if (cacheFile?.exists() == true) {
                    try {
                        val raw = javax.imageio.ImageIO.read(cacheFile) ?: throw Exception()
                        val buf = java.awt.image.BufferedImage(
                            size,
                            size,
                            java.awt.image.BufferedImage.TYPE_INT_ARGB
                        )
                        val g = buf.createGraphics()
                        g.drawImage(
                            raw.getScaledInstance(size, size, Image.SCALE_SMOOTH),
                            0,
                            0,
                            null
                        )
                        g.dispose()
                        img = ImageIcon(buf)
                    } catch (_: Exception) {
                    }
                }
                if (img == null && iconUrl.isNotBlank()) {
                    img = com.khalibre.link2command.devpanel.common.CardUtils.fetchRemoteIcon(
                        iconUrl,
                        size,
                        null,
                        false
                    )
                }
                img?.let { final -> SwingUtilities.invokeLater { onLoaded(final) } }
            }
        }
    }

    // ── Badge + label helpers ─────────────────────────────────────────────────

    /**
     * A config-only badge: clicking toggles active state but does NOT trigger
     * any filter refresh (that happens only on Save).
     */
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
                TicketsPanel.applyBadgeStyle(label); label.repaint()
                // Note: don't call refresh/save — user clicks Save explicitly
            }

            override fun mouseEntered(e: MouseEvent) {
                label.putClientProperty("hovered", true); TicketsPanel.applyBadgeStyle(label)
            }

            override fun mouseExited(e: MouseEvent) {
                label.putClientProperty("hovered", false); TicketsPanel.applyBadgeStyle(label)
            }
        })
        return label
    }

    private fun sectionLabel(text: String) = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD, font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(4)
    }

    private fun fieldLabel(text: String) = JBLabel(text).apply {
        font = font.deriveFont(font.size - 1f)
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(2)
    }

    private fun setStatus(text: String) {
        SwingUtilities.invokeLater { statusLabel.text = text }
    }
}
