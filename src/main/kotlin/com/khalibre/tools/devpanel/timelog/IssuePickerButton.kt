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
 * [items] — no API calls as the user types.
 */
class IssuePickerButton : JButton("Select ticket…") {

    private var items: List<TimeTicketInfo> = emptyList()
    private var selected: TimeTicketInfo? = null
    private var onSelected: ((TimeTicketInfo) -> Unit)? = null

    init {
        horizontalAlignment = javax.swing.SwingConstants.LEFT
        addActionListener { showPopup() }
    }

    fun setItems(newItems: List<TimeTicketInfo>) {
        items = newItems
        text = selected?.let { label(it) } ?: "Select ticket…"
    }

    fun setOnSelected(callback: (TimeTicketInfo) -> Unit) {
        onSelected = callback
    }

    fun selectedTicket(): TimeTicketInfo? = selected

    fun setSelected(ticket: TimeTicketInfo?) {
        selected = ticket
        text = ticket?.let { label(it) } ?: "Select ticket…"
    }

    private fun label(t: TimeTicketInfo) = "${t.key}: ${t.summary}"

    private fun showPopup() {
        val listModel = DefaultListModel<TimeTicketInfo>()
        items.forEach { listModel.addElement(it) }

        lateinit var list: JBList<TimeTicketInfo>
        list = JBList(listModel).apply {
            cellRenderer = javax.swing.ListCellRenderer<TimeTicketInfo> { _, value, _, isSelected, _ ->
                javax.swing.JLabel(label(value)).apply {
                    border = JBUI.Borders.empty(4, 8)
                    isOpaque = true
                    background = if (isSelected) list.selectionBackground else list.background
                    foreground = if (isSelected) list.selectionForeground else list.foreground
                }
            }
        }

        val searchField = ExtendableTextField().apply {
            emptyText.text = "Search by key or summary…"
        }
        val clearableExtension = ExtendableTextComponent.Extension.create(
            AllIcons.Actions.Close, AllIcons.Actions.CloseHovered, "Clear search"
        ) { searchField.text = "" }

        fun applyFilter() {
            val query = searchField.text.trim().lowercase()
            listModel.clear()
            val filtered = if (query.isBlank()) items
            else items.filter {
                it.key.lowercase().contains(query) || it.summary.lowercase().contains(query)
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

        list.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val choice = list.selectedValue ?: return@addListSelectionListener
                setSelected(choice)
                onSelected?.invoke(choice)
                popup.closeOk(null)
            }
        }

        popup.showUnderneathOf(this)
        SwingUtilities.invokeLater { searchField.requestFocusInWindow() }
    }
}
