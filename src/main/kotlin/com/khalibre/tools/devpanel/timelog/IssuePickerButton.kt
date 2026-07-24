package com.khalibre.tools.devpanel.timelog

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * A button showing "<Key>: <summary>" for the currently-selected ticket. Clicking it opens a
 * popup with a full-width search field (with a clear-icon extension) at the top and a filtered
 * list of tickets below. Filtering happens purely client-side against the already-loaded
 * [items] — no API calls as the user types.
 */
class IssuePickerButton : JButton("Select ticket…") {

    private var items: List<TimeTicketInfo> = emptyList()
    private var selected: TimeTicketInfo? = null
    private var onSelected: ((TimeTicketInfo) -> Unit)? = null

    private var initialSearchQuery: String = ""
    private var onSearchChanged: ((String) -> Unit)? = null

    private val maxWidth = JBUI.scale(220)
    private fun naturalPreferredSize(): Dimension = super.getPreferredSize()
    private val fixedSize: Dimension by lazy { Dimension(maxWidth, naturalPreferredSize().height) }

    init {
        horizontalAlignment = SwingConstants.LEFT
        margin = JBUI.insets(2, 4)
        addActionListener { showPopup() }
        applyLabel("Select ticket…")
    }

    /** Keeps the button's size fully fixed regardless of how long the selected ticket's text is —
     *  without this, a long "<Key>: <summary>" would grow this button (and therefore the whole
     *  Add Time dialog, since it sits in a GridLayout row) every time a different ticket is picked.
     *  Computed once from the button's natural preferred size (before any text ever grows it),
     *  then reported as a constant from here on — no code path can make this vary with text. */
    override fun getPreferredSize(): Dimension = fixedSize
    override fun getMinimumSize(): Dimension = fixedSize
    override fun getMaximumSize(): Dimension = fixedSize

    /** Sets [full] as the tooltip and displays it truncated with an ellipsis if it doesn't fit
     *  within [maxWidth]. */
    private fun applyLabel(full: String) {
        toolTipText = full
        val fm = getFontMetrics(font)
        val available = maxWidth - insets.left - insets.right - JBUI.scale(4)
        text = if (fm == null || fm.stringWidth(full) <= available) full
        else {
            var truncated = full
            while (truncated.isNotEmpty() && fm.stringWidth("$truncated…") > available) {
                truncated = truncated.dropLast(2)
            }
            "$truncated…"
        }
    }

    fun setItems(newItems: List<TimeTicketInfo>) {
        items = newItems
        applyLabel(selected?.let { label(it) } ?: "Select ticket…")
    }

    fun setOnSelected(callback: (TimeTicketInfo) -> Unit) {
        onSelected = callback
    }

    /** Pre-fills the search field with [query] the next time the popup opens. */
    fun setInitialSearchQuery(query: String) {
        initialSearchQuery = query
    }

    /** Fired on every keystroke in the search field, so the caller can persist it. */
    fun setOnSearchChanged(callback: (String) -> Unit) {
        onSearchChanged = callback
    }

    fun selectedTicket(): TimeTicketInfo? = selected

    fun setSelected(ticket: TimeTicketInfo?) {
        selected = ticket
        applyLabel(ticket?.let { label(it) } ?: "Select ticket…")
    }

    private fun label(t: TimeTicketInfo) = "${t.key}: ${t.summary}"

    private fun showPopup() {
        val listModel = DefaultListModel<TimeTicketInfo>()
        items.forEach { listModel.addElement(it) }

        // Tracks the row under the mouse cursor so hovering can highlight it the same way a
        // native combo box popup does — independent of (and in addition to) keyboard/click
        // selection, which JList's own isSelected already covers.
        var hoveredIndex = -1

        lateinit var list: JBList<TimeTicketInfo>
        list = JBList(listModel).apply {
            cellRenderer =
                javax.swing.ListCellRenderer<TimeTicketInfo> { _, value, index, isSelected, _ ->
                    javax.swing.JLabel(label(value)).apply {
                        border = JBUI.Borders.empty(4, 8)
                        isOpaque = true
                        val highlighted = isSelected || index == hoveredIndex
                        background = if (highlighted) list.selectionBackground else list.background
                        foreground = if (highlighted) list.selectionForeground else list.foreground
                    }
                }
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: java.awt.event.MouseEvent) {
                    val idx = locationToIndex(e.point)
                    val bounds = if (idx >= 0) getCellBounds(idx, idx) else null
                    val newHovered = if (bounds != null && bounds.contains(e.point)) idx else -1
                    if (newHovered != hoveredIndex) {
                        hoveredIndex = newHovered
                        repaint()
                    }
                }
            })
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    hoveredIndex = -1
                    repaint()
                }
            })
        }

        val searchField = ExtendableTextField().apply {
            emptyText.text = "Search by key or summary…"
            text = initialSearchQuery
        }
        var lastPersistedQuery = initialSearchQuery
        val clearableExtension = ExtendableTextComponent.Extension.create(
            AllIcons.Actions.Close, AllIcons.Actions.CloseHovered, "Clear search"
        ) { searchField.text = "" }

        fun applyFilter() {
            val query = searchField.text.trim()
            initialSearchQuery = query

            val lower = query.lowercase()
            listModel.clear()
            val filtered = if (lower.isBlank()) items
            else items.filter {
                it.key.lowercase().contains(lower) || it.summary.lowercase().contains(lower)
            }
            filtered.forEach { listModel.addElement(it) }
            if (searchField.extensions.contains(clearableExtension) != query.isNotEmpty()) {
                if (query.isNotEmpty()) searchField.addExtension(clearableExtension)
                else searchField.removeExtension(clearableExtension)
            }
        }

        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })

        val panel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(360, 320)
            border = JBUI.Borders.empty(6)
            add(searchField, BorderLayout.NORTH)
            add(JBScrollPane(list).apply { border = JBUI.Borders.emptyTop(6) }, BorderLayout.CENTER)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, searchField)
            .setRequestFocus(true)
            .setResizable(true)
            .createPopup()

        fun persistSearchQuery() {
            val query = searchField.text.trim()
            if (query != lastPersistedQuery) {
                lastPersistedQuery = query
                onSearchChanged?.invoke(query)
            }
        }

        searchField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) = persistSearchQuery()
        })
        popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
            override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                persistSearchQuery()
            }
        })

        list.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val choice = list.selectedValue ?: return@addListSelectionListener
                setSelected(choice)
                onSelected?.invoke(choice)
                popup.closeOk(null)
            }
        }

        applyFilter()

        popup.showUnderneathOf(this)
        SwingUtilities.invokeLater {
            searchField.requestFocusInWindow()
            searchField.selectAll()
        }
    }
}
