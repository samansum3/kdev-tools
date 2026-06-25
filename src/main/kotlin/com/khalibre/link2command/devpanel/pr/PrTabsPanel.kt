package com.khalibre.link2command.devpanel.pr

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.common.ProjectPaths
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.CompoundBorder

/**
 * Hosts multiple [PrPanel] instances behind a compact, browser-style sub-tab strip —
 * the PR Tools counterpart of [com.khalibre.link2command.devpanel.tickets.TicketTabsPanel].
 *
 * Each sub-tab keeps its own base-branch/author selection (see [PrPanel] + [PrTabsStore]),
 * so the person can keep several differently-filtered PR lists open and flip between them.
 *
 * Tabs are named A, B, C … by default, renamable via double-click, and closable via an
 * "×" affordance. At least one tab is always kept open.
 */
class PrTabsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private fun cwDir() = ProjectPaths.cwDir(project)

    private var state: PrTabsState = cwDir()?.let { PrTabsStore.load(it) }
        ?: PrTabsState(
            tabs = listOf(PrTabConfig(PrTabsStore.newTabId(), "A")),
            selectedTabId = ""
        ).let { it.copy(selectedTabId = it.tabs.first().id) }

    private val panels = mutableMapOf<String, PrPanel>()

    private val tabStripRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        isOpaque = false
    }
    private val contentHolder = JPanel(BorderLayout())

    // Reload icon pulled to the right of the sub-tab strip — same effect as the toolbar
    // sync icon, but scoped to "reload the current tab's data". Spins while that tab's
    // PrPanel is loading, replacing the old inline "Loading…" text entirely.
    private val reloadButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Reload current tab"
        isFocusPainted = false; isBorderPainted = false; isContentAreaFilled = false
        preferredSize = Dimension(22, 22); minimumSize = Dimension(22, 22)
        maximumSize = Dimension(22, 22)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { refreshActive() }
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

    init {
        val stripRowWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(22))
            preferredSize = Dimension(preferredSize.width, JBUI.scale(22))
            add(tabStripRow, BorderLayout.WEST)
            add(reloadButton, BorderLayout.EAST)
        }
        val separator = JPanel().apply {
            isOpaque = true
            background = JBColor(Color(218, 218, 218), Color(60, 63, 65))
            maximumSize = Dimension(Int.MAX_VALUE, 1)
            preferredSize = Dimension(preferredSize.width, 1)
        }
        val north = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            add(stripRowWrap)
            add(separator)
        }
        add(north, BorderLayout.NORTH)
        add(contentHolder, BorderLayout.CENTER)

        if (state.tabs.none { it.id == state.selectedTabId }) {
            state = state.copy(selectedTabId = state.tabs.first().id)
        }
        rebuildStrip()
        showTab(state.selectedTabId)
    }

    /** The currently visible tab's [PrPanel] — used e.g. by the toolbar refresh action. */
    fun activePrPanel(): PrPanel? = panels[state.selectedTabId]

    fun refreshActive() {
        activePrPanel()?.refresh()
    }

    // ── Tab lifecycle ────────────────────────────────────────────────────────

    private fun panelFor(tabId: String): PrPanel =
        panels.getOrPut(tabId) {
            PrPanel(project, tabId) { loading ->
                if (tabId == state.selectedTabId) setReloadSpinning(loading)
            }
        }

    private fun showTab(tabId: String) {
        val panel = panelFor(tabId)
        contentHolder.removeAll()
        contentHolder.add(panel, BorderLayout.CENTER)
        contentHolder.revalidate()
        contentHolder.repaint()
        panel.refresh()
    }

    private fun selectTab(tabId: String) {
        if (tabId == state.selectedTabId) return
        state = state.copy(selectedTabId = tabId)
        persist()
        rebuildStrip()
        showTab(tabId)
    }

    private fun addTab() {
        val newTab = PrTabConfig(PrTabsStore.newTabId(), PrTabsStore.nextTabName(state.tabs))
        state = state.copy(tabs = state.tabs + newTab, selectedTabId = newTab.id)
        persist()
        rebuildStrip()
        showTab(newTab.id)
    }

    private fun closeTab(tabId: String) {
        if (state.tabs.size <= 1) return // always keep at least one tab open
        val idx = state.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return
        val remaining = state.tabs.filter { it.id != tabId }
        val newSelected = if (state.selectedTabId == tabId) {
            remaining.getOrNull(idx.coerceAtMost(remaining.size - 1))?.id ?: remaining.first().id
        } else state.selectedTabId

        state = state.copy(tabs = remaining, selectedTabId = newSelected)
        panels.remove(tabId)
        cwDir()?.let { PrTabsStore.deleteTabState(it, tabId) }
        persist()
        rebuildStrip()
        showTab(newSelected)
    }

    private fun renameTab(tabId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            rebuildStrip() // revert visual to the old name
            return
        }
        state = state.copy(tabs = state.tabs.map { if (it.id == tabId) it.copy(name = trimmed) else it })
        persist()
        rebuildStrip()
    }

    private fun persist() {
        cwDir()?.let { PrTabsStore.save(it, state) }
    }

    // ── Tab strip UI ─────────────────────────────────────────────────────────

    private fun rebuildStrip() {
        tabStripRow.removeAll()
        state.tabs.forEach { tab -> tabStripRow.add(buildTabPill(tab)) }
        tabStripRow.add(buildAddButton())
        tabStripRow.revalidate()
        tabStripRow.repaint()
    }

    private fun buildAddButton(): JComponent {
        val normalFg = JBUI.CurrentTheme.Label.disabledForeground()
        val btn = JLabel("+").apply {
            font = font.deriveFont(Font.BOLD, font.size2D)
            foreground = normalFg
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(0, 10, 2, 4)
            toolTipText = "Add tab"
        }
        btn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = addTab()
            override fun mouseEntered(e: MouseEvent) {
                btn.foreground = Color(24, 95, 165)
            }

            override fun mouseExited(e: MouseEvent) {
                btn.foreground = normalFg
            }
        })
        return btn
    }

    /**
     * Flat, text-only tab — no pill/badge background, just a name, an "×" to close, and a thin
     * accent underline on whichever tab is selected.
     */
    private fun buildTabPill(tab: PrTabConfig): JComponent {
        val isSelected = tab.id == state.selectedTabId
        val normalFg = JBUI.CurrentTheme.Label.disabledForeground()
        val selectedFg = JBUI.CurrentTheme.Label.foreground()
        val accent = Color(24, 95, 165)
        val transparent = Color(0, 0, 0, 0)

        val nameLabel = JLabel(tab.name).apply {
            font = font.deriveFont(font.size - 1f)
            foreground = if (isSelected) selectedFg else normalFg
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(0, 2, 0, 4)
        }
        val closeLabel = JLabel("\u00D7").apply { // ×
            font = font.deriveFont(font.size - 1f)
            foreground = normalFg
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(0, 0, 0, 2)
            toolTipText = if (state.tabs.size > 1) "Close tab" else "Can't close the last tab"
        }

        val tabPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = CompoundBorder(
                JBUI.Borders.customLine(if (isSelected) accent else transparent, 0, 0, 2, 0),
                JBUI.Borders.empty(2, 6, 4, 4)
            )
            add(nameLabel, BorderLayout.CENTER)
            add(closeLabel, BorderLayout.EAST)
        }

        val selectListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) startRename(tab, nameLabel, tabPanel) else selectTab(tab.id)
            }

            override fun mouseEntered(e: MouseEvent) {
                if (!isSelected) nameLabel.foreground = accent
            }

            override fun mouseExited(e: MouseEvent) {
                if (!isSelected) nameLabel.foreground = normalFg
            }
        }
        nameLabel.addMouseListener(selectListener)
        tabPanel.addMouseListener(selectListener)

        closeLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                closeTab(tab.id)
            }

            override fun mouseEntered(e: MouseEvent) {
                closeLabel.foreground = if (isSelected) Color(163, 45, 45) else selectedFg
            }

            override fun mouseExited(e: MouseEvent) {
                closeLabel.foreground = normalFg
            }
        })

        return tabPanel
    }

    /** Double-click-to-rename: swaps the name label for an inline text field. */
    private fun startRename(tab: PrTabConfig, nameLabel: JLabel, tabPanel: JPanel) {
        val field = JTextField(tab.name).apply {
            font = nameLabel.font
            border = JBUI.Borders.empty(0, 2)
            columns = maxOf(3, tab.name.length + 1)
        }

        fun commit() {
            renameTab(tab.id, field.text)
        }

        field.addActionListener { commit() } // Enter
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = commit()
        })
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) rebuildStrip()
            }
        })

        tabPanel.remove(nameLabel)
        tabPanel.add(field, BorderLayout.CENTER)
        tabPanel.revalidate()
        tabPanel.repaint()
        field.requestFocusInWindow()
        field.selectAll()
    }
}
