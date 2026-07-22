package com.khalibre.tools.devpanel.timelog

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.khalibre.tools.devpanel.common.CardUtils
import com.khalibre.tools.devpanel.common.ProjectPaths
import com.khalibre.tools.devpanel.tickets.JiraUserService
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*
import javax.swing.*
import javax.swing.Timer
import javax.swing.border.CompoundBorder

class TimeLogPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val cwDir get() = ProjectPaths.cwDir(project)

    // Monday of the currently displayed week.
    private var weekStart: LocalDate = LocalDate.now().with(DayOfWeek.MONDAY)

    private var selectedAccountId: String? = null
    private var myAccountId: String? = null

    private val rangeLabel = JBLabel("", SwingConstants.CENTER).apply {
        font = font.deriveFont(font.size + 1f)
    }
    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private val userCombo: JComboBox<JiraUserService.JiraUser?> = buildUserCombo().apply {
        addActionListener {
            val user = selectedItem as? JiraUserService.JiraUser ?: return@addActionListener
            if (user.accountId == selectedAccountId) return@addActionListener
            selectedAccountId = user.accountId
            displayedWeek = null
            currentEntries = null
            reload()
        }
    }

    // Reload icon — same styling/spin pattern as TicketTabsPanel's reloadButton. Spins both on
    // manual click and while a week switch (prev/next) is loading.
    private val reloadButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Reload"
        isFocusPainted = false; isBorderPainted = false; isContentAreaFilled = false
        preferredSize = Dimension(22, 22); minimumSize = Dimension(22, 22)
        maximumSize = Dimension(22, 22)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { reload() }
    }
    private var reloadSpinTimer: Timer? = null
    private val reloadSpinIcons = listOf(
        AllIcons.Actions.Refresh,
        AllIcons.Process.Step_1,
        AllIcons.Process.Step_2,
        AllIcons.Process.Step_3,
        AllIcons.Process.Step_4
    )

    private fun setReloadSpinning(spinning: Boolean) {
        reloadSpinTimer?.stop(); reloadSpinTimer = null
        if (spinning) {
            var frame = 0
            reloadSpinTimer = Timer(120) {
                reloadButton.icon = reloadSpinIcons[frame++ % reloadSpinIcons.size]
            }.also { it.start() }
            reloadButton.isEnabled = false
        } else {
            reloadButton.icon = AllIcons.Actions.Refresh; reloadButton.isEnabled = true
        }
    }

    private fun iconNavButton(icon: javax.swing.Icon, tooltip: String, action: () -> Unit) =
        JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false; isBorderPainted = false; isContentAreaFilled = false
            preferredSize = Dimension(22, 22); minimumSize = Dimension(22, 22)
            maximumSize = Dimension(22, 22)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { action() }
        }

    private val daysGrid = JPanel(GridLayout(1, 5, 8, 0))
    private val separator = JPanel().apply {
        isOpaque = true
        background = JBColor(Color(218, 218, 218), Color(60, 63, 65))
        maximumSize = Dimension(Int.MAX_VALUE, 1)
        preferredSize = Dimension(preferredSize.width, 1)
    }

    // What's currently rendered, and for which week — null means "nothing to show yet" (as
    // opposed to an empty list, which means "we checked, there's genuinely nothing logged").
    private var currentEntries: List<WorklogEntry>? = null
    private var displayedWeek: LocalDate? = null

    init {
        val heading = JPanel(BorderLayout()).apply {
            add(buildHeader(), BorderLayout.NORTH)
            add(Box.createVerticalStrut(JBUI.scale(4)), BorderLayout.CENTER)
            add(separator, BorderLayout.SOUTH)
        }
        add(heading, BorderLayout.NORTH)

        val scroll = JBScrollPane(daysGrid).apply {
            border = JBUI.Borders.emptyTop(10)
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        initUserAndLoad()
    }

    private fun buildUserCombo(): JComboBox<JiraUserService.JiraUser?> {
        val hoverBg = JBUI.CurrentTheme.ActionButton.hoverBackground()

        val combo = object : ComboBox<JiraUserService.JiraUser?>() {
            var isHovered = false

            override fun getInsets(): Insets {
                val hasAvatar = selectedItem is JiraUserService.JiraUser
                return if (hasAvatar) Insets(0, 0, 0, 6) else Insets(1, 4, 1, 4)
            }

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
                return Dimension(
                    content.width + i.left + i.right,
                    content.height + i.top + i.bottom
                )
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
                if (isHovered) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                    )
                    g2.color = hoverBg
                    val w = width.toDouble()
                    val h = height.toDouble()
                    val r = JBUI.scale(4).toDouble()
                    g2.fill(java.awt.geom.RoundRectangle2D.Double(0.0, 0.0, w, h, r, r))
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }
        combo.isEditable = false
        combo.isOpaque = false
        combo.background =
            Color(0, 0, 0, 0) // always transparent — UI delegate's own fill is a no-op now
        combo.border = JBUI.Borders.empty()
        combo.font = combo.font.deriveFont(combo.font.size - 2f)
        combo.putClientProperty("JComboBox.isBorderless", true)
        combo.putClientProperty("JComboBox.isTableCellEditor", false)

        for (comp in combo.components) {
            if (comp is JButton) {
                comp.isVisible = false
            }
        }
        combo.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                combo.isHovered = true; combo.repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                combo.isHovered = false; combo.repaint()
            }
        })

        fun rerenderComboToContent() {
            combo.invalidate()   // mark this component's cached size as stale
            combo.revalidate()   // walk up to the nearest validate root and schedule layout
            combo.repaint()
        }

        combo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                isOpaque = index != -1 && isSelected
                border = JBUI.Borders.empty(1, 0)
                val user = value as? JiraUserService.JiraUser
                val caret = if (index == -1) "  ▾" else ""
                if (user == null) {
                    text = "Unassigned$caret"
                    icon = null
                    foreground =
                        if (isSelected) foreground else JBUI.CurrentTheme.Label.disabledForeground()
                    return this
                }
                text = "${user.displayName}$caret"
                foreground = if (isSelected) foreground
                else if (user.accountId == myAccountId)
                    Color(59, 109, 17) else JBUI.CurrentTheme.Label.disabledForeground()
                icon = resolveAssigneeAvatar(user, ::rerenderComboToContent)
                iconTextGap = 4
                return this
            }
        }

        combo.addActionListener {
            rerenderComboToContent()
        }
        return combo
    }

    private fun resolveAssigneeAvatar(
        user: JiraUserService.JiraUser,
        onLoaded: () -> Unit
    ): ImageIcon? =
        JiraUserService.resolveAvatarIcon(user, cwDir, onLoaded)

    // ── Header: [user combo]  [◀ date range ▶] (tightly grouped)  ...  [reload icon] ─────────

    private fun buildHeader(): JComponent {
        val prevBtn = iconNavButton(AllIcons.Actions.Back, "Previous week") {
            weekStart = weekStart.minusWeeks(1); reload()
        }
        val nextBtn = iconNavButton(AllIcons.Actions.Forward, "Next week") {
            weekStart = weekStart.plusWeeks(1); reload()
        }
        val navGroup = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(prevBtn)
            add(rangeLabel)
            add(nextBtn)
        }
        val userWrap = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(userCombo)
        }
        val reloadWrap = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(reloadButton)
        }
        return JPanel(BorderLayout()).apply {
            add(userWrap, BorderLayout.WEST)
            add(navGroup, BorderLayout.CENTER)
            add(reloadWrap, BorderLayout.EAST)
        }
    }

    private fun updateRangeLabel() {
        val weekEnd = weekStart.plusDays(4)
        val fmt = DateTimeFormatter.ofPattern("MMM d")
        rangeLabel.text =
            "${weekStart.format(fmt)} – ${weekEnd.format(DateTimeFormatter.ofPattern("d, yyyy"))}"
    }

    // ── Initial load: resolve users + "me" before the very first fetch ─────────

    private fun initUserAndLoad() {
        val dir = cwDir
        if (dir == null) {
            reload()
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val users = JiraUserService.loadUsers(dir)
            val me = JiraUserService.currentAccountId(dir)
            SwingUtilities.invokeLater {
                val meUser = users.firstOrNull { it.accountId == me }
                val (meFirst, rest) = users.partition { it.accountId == me }
                val ordered = meFirst + rest
                val model = DefaultComboBoxModel<JiraUserService.JiraUser>()
                ordered.forEach { model.addElement(it) }
                userCombo.model = model
                if (meUser != null) userCombo.selectedItem = meUser

                myAccountId = me
                selectedAccountId = me ?: users.firstOrNull()?.accountId
                reload()
            }
        }
    }

    // ── Reload ───────────────────────────────────────────────────────────────

    /** Called after a successful add, by the week-navigation buttons, by the user combo, and by
     *  the reload icon. */
    fun reload() {
        updateRangeLabel()
        val dir = cwDir
        val accountId = selectedAccountId
        if (dir == null || accountId == null) {
            statusLabel.text = if (dir == null) "No git project detected" else "Loading…"
            currentEntries = emptyList()
            displayedWeek = weekStart
            buildEmptyGrid()
            return
        }

        val requestedWeek = weekStart

        if (displayedWeek != requestedWeek) {
            // Switching to a week/user we're not currently showing — never leave the previous
            // selection's columns on screen under the new header. Show cache if we have one,
            // otherwise an explicit Loading placeholder.
            val cached = TimeLogService.loadCachedWeek(dir, accountId, requestedWeek)
            displayedWeek = requestedWeek
            if (cached != null) {
                currentEntries = cached
                renderGrid(cached)
            } else {
                currentEntries = null
                buildLoadingGrid()
            }
        }
        // else: same week/user already on screen (manual refresh, or right after an optimistic
        // add) — leave the current view as-is; only the background fetch below may replace it.

        statusLabel.text = if (currentEntries == null) "Loading…" else ""
        setReloadSpinning(true)
        val weekEnd = requestedWeek.plusDays(4)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = TimeLogService.fetchWeekWorklogs(requestedWeek, weekEnd, dir, accountId)
            result.onSuccess { entries ->
                TimeLogService.cacheWeek(
                    dir,
                    accountId,
                    requestedWeek,
                    entries
                )
            }
            SwingUtilities.invokeLater {
                // The user may have switched weeks/users again before this came back — ignore
                // stale responses rather than overwriting whatever's actually on screen now.
                if (requestedWeek != weekStart || accountId != selectedAccountId) return@invokeLater
                setReloadSpinning(false)
                result.onSuccess { entries ->
                    statusLabel.text = ""
                    currentEntries = entries
                    renderGrid(entries)
                }.onFailure {
                    if (currentEntries == null) {
                        statusLabel.text = "✘ ${it.message}"
                        buildEmptyGrid()
                    } else {
                        // We already have cached/optimistic data showing — don't blow it away
                        // over a failed background refresh, just surface the error quietly.
                        statusLabel.text = "✘ ${it.message} (showing cached data)"
                    }
                }
            }
        }
    }

    /** Instantly reflects a just-added worklog in the grid without waiting on a fresh fetch —
     *  the background reload() triggered right after this will reconcile with Jira's true state.
     *  Only applies when viewing your own log: Jira always attributes a new worklog to the
     *  authenticated user, so if you're currently viewing someone else's log, this entry doesn't
     *  actually belong in their columns and is skipped. */
    fun addEntryOptimistically(entry: WorklogEntry) {
        if (selectedAccountId != myAccountId) return
        val merged = (currentEntries ?: emptyList()) + entry
        currentEntries = merged
        renderGrid(merged)
        val dir = cwDir
        val accountId = selectedAccountId
        val week = weekStart
        if (dir != null && accountId != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                TimeLogService.cacheWeek(dir, accountId, week, merged)
            }
        }
    }

    private fun buildLoadingGrid() {
        daysGrid.removeAll()
        for (i in 0 until 5) {
            val date = weekStart.plusDays(i.toLong())
            daysGrid.add(buildDayColumn(date, null))
        }
        daysGrid.revalidate()
        daysGrid.repaint()
    }

    private fun buildEmptyGrid() {
        daysGrid.removeAll()
        for (i in 0 until 5) {
            val date = weekStart.plusDays(i.toLong())
            daysGrid.add(buildDayColumn(date, emptyList()))
        }
        daysGrid.revalidate()
        daysGrid.repaint()
    }

    private fun renderGrid(entries: List<WorklogEntry>) {
        val byDate = entries.groupBy { it.date }
        daysGrid.removeAll()
        for (i in 0 until 5) {
            val date = weekStart.plusDays(i.toLong())
            daysGrid.add(buildDayColumn(date, byDate[date] ?: emptyList()))
        }
        daysGrid.revalidate()
        daysGrid.repaint()
    }

    // ── Day column ───────────────────────────────────────────────────────────

    /** [items] == null means "still loading, we don't know yet" — distinct from an empty list,
     *  which means we already know nothing was logged that day. */
    private fun buildDayColumn(date: LocalDate, items: List<WorklogEntry>?): JComponent {
        val totalMinutes = items?.sumOf { parseDurationToMinutes(it.timeSpent) } ?: 0
        val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(6)
            add(JBLabel("$dayName ${date.dayOfMonth}").apply {
                font = font.deriveFont(Font.BOLD, font.size - 1f)
            }, BorderLayout.WEST)
            add(JBLabel(if (items == null) "" else formatMinutes(totalMinutes)).apply {
                font = font.deriveFont(font.size - 2f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }, BorderLayout.EAST)
        }

        val cardsBox = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            when {
                items == null -> add(JBLabel("Loading…").apply {
                    font = font.deriveFont(font.size - 2f)
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(8)
                })

                items.isEmpty() -> add(JBLabel("No time logged").apply {
                    font = font.deriveFont(font.size - 2f)
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(8)
                })

                else -> items.forEach { entry ->
                    add(buildEntryCard(entry))
                    add(Box.createVerticalStrut(6))
                }
            }
            add(CardUtils.makeActionButton("+ Add time") {
                AddTimeDialog(project, date) { entry ->
                    addEntryOptimistically(entry)
                    reload()
                }.show()
            }.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(Box.createVerticalGlue())
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(cardsBox).apply {
                border = JBUI.Borders.empty()
                verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }
    }

    private fun buildEntryCard(entry: WorklogEntry): JComponent {
        val card = object : JPanel() {
            override fun getMaximumSize(): Dimension =
                Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = CompoundBorder(
                BorderFactory.createLineBorder(
                    JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, true
                ),
                JBUI.Borders.empty(6, 8)
            )
        }
        val top = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(entry.issueKey).apply {
                font = font.deriveFont(Font.BOLD, font.size - 2f)
            }, BorderLayout.WEST)
            add(JBLabel(entry.timeSpent).apply {
                font = font.deriveFont(font.size - 2f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }, BorderLayout.EAST)
        }
        val summaryText = entry.comment.ifBlank { entry.issueSummary }
        val summary = JBLabel("<html>${escapeHtml(summaryText)}</html>").apply {
            font = font.deriveFont(font.size - 2f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        card.add(top)
        card.add(summary)
        return card
    }

    private fun escapeHtml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun parseDurationToMinutes(timeSpent: String): Int {
        var minutes = 0
        Regex("(\\d+)([hHmMdD])").findAll(timeSpent).forEach { m ->
            val value = m.groupValues[1].toIntOrNull() ?: 0
            when (m.groupValues[2].lowercase()) {
                "h" -> minutes += value * 60
                "m" -> minutes += value
                "d" -> minutes += value * 8 * 60 // Jira's default "1 day = 8 hours"
            }
        }
        return minutes
    }

    private fun formatMinutes(totalMinutes: Int): String {
        if (totalMinutes == 0) return "0h"
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}
