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
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * A button showing "<Key>: <summary>" for the currently-selected ticket. Clicking it opens a
 * popup with a full-width search field (with a clear-icon extension) at the top and a filtered
 * list of tickets below. Filtering happens purely client-side against the already-loaded
 * [items] — no API calls as the user types. The list highlights whichever row the mouse is over
 * (like a native combo box dropdown) independently of the actual committed selection, which only
 * happens on click or Enter.
 */
class IssuePickerButton : JButton("Select ticket…") {

    private var items: List<TimeTicketInfo> = emptyList()
    private var selected: TimeTicketInfo? = null
    private var onSelected: ((TimeTicketInfo) -> Unit)? = null

    private var initialSearchQuery: String = ""
    private var onSearchChanged: ((String) -> Unit)? = null

    private val maxWidth = JBUI.scale(220)

    init {
        horizontalAlignment = javax.swing.SwingConstants.LEFT
        margin = JBUI.insets(2, 6)
        addActionListener { showPopup() }
        applyLabel("Select ticket…")
    }

    /** Keeps the button's width capped regardless of how long the selected ticket's text is —
     *  without this, a long "<Key>: <summary>" would grow this button (and therefore the whole
     *  Add Time dialog, since it sits in a GridLayout row) every time a different ticket is picked. */
    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        return Dimension(minOf(base.width, maxWidth), base.height)
    }

    override fun getMaximumSize(): Dimension = preferredSize

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
                truncated = truncated.dropLast(1)
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

        val list = JBList(listModel).apply {
            fixedCellHeight = JBUI.scale(24)
            cellRenderer = javax.swing.ListCellRenderer<TimeTicketInfo> { l, value, _, isSelected, _ ->
                javax.swing.JLabel(label(value)).apply {
                    border = JBUI.Borders.empty(4, 8)
                    isOpaque = true
                    background = if (isSelected) l.selectionBackground else l.background
                    foreground = if (isSelected) l.selectionForeground else l.foreground
                }
            }
        }

        // Hover highlights the row under the mouse — same feel as a native combo box dropdown —
        // entirely separate from committing a choice, which only happens on click or Enter.
        list.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val idx = list.locationToIndex(e.point)
                if (idx >= 0) list.selectedIndex = idx
            }
        })

        fun commit(ticket: TimeTicketInfo) {
            setSelected(ticket)
            onSelected?.invoke(ticket)
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
            if (filtered.isNotEmpty()) list.selectedIndex = 0
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

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = list.locationToIndex(e.point)
                val ticket = listModel.elementAt(idx) ?: return
                commit(ticket)
                popup.closeOk(null)
            }
        })
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        if (listModel.size() > 0) {
                            list.selectedIndex = (list.selectedIndex + 1).coerceIn(0, listModel.size() - 1)
                            e.consume()
                        }
                    }

                    KeyEvent.VK_UP -> {
                        if (listModel.size() > 0) {
                            list.selectedIndex = (list.selectedIndex - 1).coerceIn(0, listModel.size() - 1)
                            e.consume()
                        }
                    }

                    KeyEvent.VK_ENTER -> {
                        list.selectedValue?.let { commit(it) }
                        popup.closeOk(null)
                        e.consume()
                    }
                }
            }
        })

        // Apply the remembered query's filter before showing, then move selection to whichever
        // item (if any) matches the currently-selected ticket.
        applyFilter()
        selected?.let { current ->
            val idx = (0 until listModel.size()).firstOrNull { listModel.elementAt(it).key == current.key }
            if (idx != null) list.selectedIndex = idx
        }

        popup.showUnderneathOf(this)
        SwingUtilities.invokeLater { searchField.requestFocusInWindow() }
    }
}
