package com.khalibre.link2command.devpanel.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.tickets.JiraMetaService
import com.khalibre.link2command.devpanel.tickets.TicketsPanel
import java.awt.*
import java.io.File
import javax.swing.*

/**
 * "Ticket Config" sub-tab inside the Config tab.
 *
 * Sections
 * ────────
 * TICKETS
 *   Per-type done-status multi-selects  (one JBList per issue type)
 *   Excluded statuses multi-select      (hidden from Tickets > Status badges)
 *   Excluded types multi-select         (hidden from Tickets > type/not-type badges)
 *
 * ACTIONS
 *   [Reload ticket statuses]  [Reload ticket types]
 */
class TicketConfigPanel(
    private val getTicketsPanel: () -> TicketsPanel?,
    private val getCwDir: () -> File?
) : JPanel(BorderLayout()) {

    // ── State loaded from DevConfig ──────────────────────────────────────────
    private var allStatuses: List<String> = emptyList()
    private var allTypes: List<JiraMetaService.IssueTypeInfo> = emptyList()

    // Dynamic per-type done-status selectors  (typeName → JList<String>)
    private val doneStatusLists = mutableMapOf<String, JList<String>>()

    // Single excluded-status list and excluded-type list
    private lateinit var excludedStatusList: JList<String>
    private lateinit var excludedTypeList: JList<String>

    // Container rebuilt when meta changes
    private val doneStatusesContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    init {
        border = JBUI.Borders.empty(10, 12)
        buildUi()
        loadMetaAndConfig()
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private fun buildUi() {
        val form = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }

        // ── TICKETS section label ────────────────────────────────────────────
        form.add(sectionLabel("TICKETS"))
        form.add(Box.createVerticalStrut(8))

        // Placeholder — rebuilt by rebuildDoneStatusInputs() once meta is loaded
        form.add(doneStatusesContainer)
        form.add(Box.createVerticalStrut(12))

        // ── Excluded statuses ────────────────────────────────────────────────
        form.add(fieldLabel("Excluded statuses  (hidden from Status filter badges)"))
        form.add(Box.createVerticalStrut(4))
        excludedStatusList = buildCheckList(emptyList())
        form.add(wrapInScrollPane(excludedStatusList, 120))
        form.add(Box.createVerticalStrut(12))

        // ── Excluded types ───────────────────────────────────────────────────
        form.add(fieldLabel("Excluded types  (hidden from type / not-type filter badges)"))
        form.add(Box.createVerticalStrut(4))
        excludedTypeList = buildCheckList(emptyList())
        form.add(wrapInScrollPane(excludedTypeList, 120))
        form.add(Box.createVerticalStrut(20))

        // ── ACTIONS section ──────────────────────────────────────────────────
        form.add(sectionLabel("ACTIONS"))
        form.add(Box.createVerticalStrut(8))

        val actionsRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        actionsRow.add(JButton("Reload ticket statuses").apply {
            addActionListener { reloadStatuses() }
        })
        actionsRow.add(JButton("Reload ticket types").apply {
            addActionListener { reloadTypes() }
        })
        form.add(actionsRow)
        form.add(Box.createVerticalStrut(8))
        form.add(statusLabel)

        // ── Save button ──────────────────────────────────────────────────────
        form.add(Box.createVerticalStrut(16))
        val saveBtn = JButton("Save Ticket Config").apply { addActionListener { saveConfig() } }
        val saveRow =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(saveBtn) }
        form.add(saveRow)

        // Filler
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
                rebuildListModels()
                applyConfig(DevConfig.load().ticket)
                setStatus(
                    if (statuses.isEmpty() && types.isEmpty())
                        "No cached data — configure Jira credentials in Config and click Reload." else ""
                )
            }
        }
    }

    // ── Rebuild list models when meta changes ─────────────────────────────────

    private fun rebuildListModels() {
        // Excluded status list
        replaceListModel(excludedStatusList, allStatuses)

        // Excluded type list
        replaceListModel(excludedTypeList, allTypes.map { it.name })

        // Per-type done-status selectors
        rebuildDoneStatusInputs()
    }

    private fun rebuildDoneStatusInputs() {
        doneStatusesContainer.removeAll()
        doneStatusLists.clear()

        if (allTypes.isEmpty()) {
            doneStatusesContainer.add(JBLabel("<html><i>No type data cached. Click \"Reload ticket types\".</i></html>").apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                border = JBUI.Borders.empty(4, 0)
            })
        } else {
            allTypes.forEach { typeInfo ->
                doneStatusesContainer.add(fieldLabel("${typeInfo.name} done statuses"))
                doneStatusesContainer.add(Box.createVerticalStrut(3))
                val list = buildCheckList(allStatuses)
                doneStatusLists[typeInfo.name] = list
                doneStatusesContainer.add(wrapInScrollPane(list, 100))
                doneStatusesContainer.add(Box.createVerticalStrut(10))
            }
        }
        doneStatusesContainer.revalidate()
        doneStatusesContainer.repaint()
    }

    // ── Apply saved config to UI ──────────────────────────────────────────────

    private fun applyConfig(cfg: TicketConfig) {
        // Per-type done statuses
        doneStatusLists.forEach { (typeName, list) ->
            val saved = cfg.doneStatusesByType[typeName] ?: emptyList()
            selectItemsInList(list, saved)
        }

        // Excluded statuses
        selectItemsInList(excludedStatusList, cfg.excludedStatuses)

        // Excluded types
        selectItemsInList(excludedTypeList, cfg.excludedTypes)
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun saveConfig() {
        val doneByType = doneStatusLists.mapValues { (_, list) ->
            list.selectedValuesList
        }
        val excludedStatuses = excludedStatusList.selectedValuesList
        val excludedTypes = excludedTypeList.selectedValuesList

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
        setStatus("  Saved ✓")
        Timer(2500) { setStatus("") }.apply { isRepeats = false; start() }
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
                    rebuildListModels()
                    applyConfig(DevConfig.load().ticket)
                    setStatus(if (statuses.isEmpty()) "No statuses returned — check Jira credentials." else "  Statuses reloaded ✓")
                    Timer(3000) { setStatus("") }.apply { isRepeats = false; start() }
                    getTicketsPanel()?.reloadMetaBadges()
                }
            } else {
                SwingUtilities.invokeLater { setStatus("No git repo found.") }
            }
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
                    rebuildListModels()
                    applyConfig(DevConfig.load().ticket)
                    setStatus(if (types.isEmpty()) "No types returned — check Jira credentials." else "  Types reloaded ✓")
                    Timer(3000) { setStatus("") }.apply { isRepeats = false; start() }
                    getTicketsPanel()?.reloadMetaBadges()
                }
            } else {
                SwingUtilities.invokeLater { setStatus("No git repo found.") }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a JList that supports multiple selection using checkboxes rendered via custom cell renderer. */
    private fun buildCheckList(items: List<String>): JList<String> {
        val model = DefaultListModel<String>().also { m -> items.forEach { m.addElement(it) } }
        return JList(model).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            cellRenderer = CheckboxListCellRenderer()
            visibleRowCount = -1
        }
    }

    private fun replaceListModel(list: JList<String>, items: List<String>) {
        val selected = list.selectedValuesList.toSet()
        val model = DefaultListModel<String>().also { m -> items.forEach { m.addElement(it) } }
        list.model = model
        selectItemsInList(list, selected.toList())
    }

    private fun selectItemsInList(list: JList<String>, toSelect: List<String>) {
        if (toSelect.isEmpty()) {
            list.clearSelection(); return
        }
        val model = list.model
        val indices = (0 until model.size)
            .filter { model.getElementAt(it) in toSelect }
            .toIntArray()
        if (indices.isEmpty()) list.clearSelection()
        else list.selectedIndices = indices
    }

    private fun wrapInScrollPane(list: JList<String>, preferredHeight: Int): JScrollPane =
        JScrollPane(list).apply {
            border = javax.swing.BorderFactory.createLineBorder(
                JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1
            )
            preferredSize = Dimension(Int.MAX_VALUE, preferredHeight)
            maximumSize = Dimension(Int.MAX_VALUE, preferredHeight)
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

    private fun sectionLabel(text: String) = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD, font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        border = JBUI.Borders.emptyTop(4)
    }

    private fun fieldLabel(text: String) = JBLabel(text).apply {
        font = font.deriveFont(font.size - 1f)
        border = JBUI.Borders.emptyTop(2)
        alignmentX = LEFT_ALIGNMENT
    }

    private fun setStatus(text: String) {
        SwingUtilities.invokeLater { statusLabel.text = text }
    }

    // ── Checkbox cell renderer ────────────────────────────────────────────────

    private inner class CheckboxListCellRenderer : ListCellRenderer<String> {
        private val check = JCheckBox().apply { isOpaque = true; border = JBUI.Borders.empty(1, 4) }
        override fun getListCellRendererComponent(
            list: JList<out String>, value: String, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            check.text = value
            check.isSelected = isSelected
            check.background = if (isSelected) list.selectionBackground else list.background
            check.foreground = if (isSelected) list.selectionForeground else list.foreground
            check.font = list.font
            return check
        }
    }
}
