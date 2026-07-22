package com.khalibre.tools.devpanel.tickets

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class AssigneeUIService(private val tkPanel: TicketsPanel) {

    // Assignable users for the assignee dropdown (per-project), loaded once from cache/Jira.
    // "Unassigned" is represented as a null JiraUserService.JiraUser in the combo model.
    private var assignableUsers: List<JiraUserService.JiraUser> = emptyList()

    init {
        loadAssignableUsers()
    }

    private fun loadAssignableUsers() {
        val cw = tkPanel.cwDir() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val users = JiraUserService.loadUsers(cw)
            SwingUtilities.invokeLater {
                assignableUsers = users
                // Re-render so any already-built assignee dropdowns get populated with
                // the full user list instead of just their own ticket's current assignee.
                if (tkPanel.allLoadedTickets.isNotEmpty()) tkPanel.applySearch()
            }
        }
    }

    // ── Assignee dropdown ─────────────────────────────────────────────────────

    /**
     * Builds the per-card assignee dropdown: same avatar+name style as PR Tools > author,
     * but borderless (no label) and inline in the card's meta row.
     * "Unassigned" is always the first item. Selecting a different user updates the
     * ticket's assignee in Jira, then refreshes just that one ticket's data.
     */
    fun buildAssigneeCombo(ticket: JiraTicket): JComboBox<JiraUserService.JiraUser?> {
        val hoverBg = JBUI.CurrentTheme.ActionButton.hoverBackground()

        val combo = object : com.intellij.openapi.ui.ComboBox<JiraUserService.JiraUser?>() {
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

        val unassignedOption: JiraUserService.JiraUser? = null
        val ticketAssignee =
            assignableUsers.firstOrNull { it.accountId == ticket.assigneeAccountId }

        val loggedInAccountId = currentUserAccountId()
        val (loggedInFirst, rest) = assignableUsers.partition { it.accountId == loggedInAccountId }
        val orderedUsers = loggedInFirst + rest

        val model = DefaultComboBoxModel<JiraUserService.JiraUser?>()
        model.addElement(unassignedOption)
        orderedUsers.forEach { model.addElement(it) }

        val selectedUser = ticketAssignee ?: ticket.assigneeAccountId?.let { accountId ->
            JiraUserService.JiraUser(
                accountId,
                ticket.assigneeName ?: accountId,
                ticket.assigneeEmail,
                null
            )
                .also { model.addElement(it) }
        }
        combo.model = model
        combo.selectedItem = selectedUser

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
                else if (user.accountId == currentUserAccountId())
                    Color(59, 109, 17) else JBUI.CurrentTheme.Label.disabledForeground()
                icon = resolveAssigneeAvatar(user)
                iconTextGap = 4
                return this
            }
        }

        fun rerenderComboToContent() {
            combo.invalidate()   // mark this component's cached size as stale
            combo.revalidate()   // walk up to the nearest validate root and schedule layout
            combo.repaint()
        }

        combo.addActionListener {
            val selected = combo.selectedItem as? JiraUserService.JiraUser
            if (selected?.accountId == ticket.assigneeAccountId) return@addActionListener
            if (selected == null && ticket.assigneeAccountId == null) return@addActionListener
            rerenderComboToContent()
            onAssigneeChanged(ticket, selected)
        }

        return combo
    }

    private fun currentUserAccountId(): String? =
        assignableUsers.firstOrNull { it.emailAddress == tkPanel.currentUserEmail }?.accountId

    private fun onAssigneeChanged(
        ticket: JiraTicket,
        newAssignee: JiraUserService.JiraUser?
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val cwDir = tkPanel.cwDir()
            val result =
                JiraUserService.updateAssignee(ticket.key, newAssignee?.accountId, cwDir)
            if (result.isFailure) {
                SwingUtilities.invokeLater {
                    tkPanel.setStatus("✗ ${ticket.key}: ${result.exceptionOrNull()?.message?.take(80)}")
                }
                return@executeOnPooledThread
            }
            // Per the "load new data but not the whole list" requirement: re-fetch just this
            // one ticket (scoped via "AND key = <key>" against the active filter JQL) instead
            // of reloading everything.
            val refreshed = try {
                JiraService.refreshTicket(tkPanel.currentJql, ticket.key, cwDir)
            } catch (_: Exception) {
                null
            }
            SwingUtilities.invokeLater {
                if (refreshed != null) {
                    // Ticket still matches current filters — replace it in place.
//                    tkPanel.allLoadedTickets =
//                        tkPanel.allLoadedTickets.map { if (it.key == ticket.key) refreshed else it }
                    ticket.assigneeAccountId = refreshed.assigneeAccountId
                    ticket.assigneeName = refreshed.assigneeName
                    ticket.assigneeEmail = refreshed.assigneeEmail
                } else {
                    // No longer matches current filters (e.g. an "Unassigned"/"My Tasks" filter
                    // is active and the new assignee no longer satisfies it) — remove it,
                    // leaving every other ticket untouched.
                    tkPanel.allLoadedTickets =
                        tkPanel.allLoadedTickets.filter { it.key != ticket.key }
                    tkPanel.applySearch()
                }
            }
        }
    }

    /** Avatar loader for assignee combos — delegates to the shared resolver in JiraUserService,
     *  which both this combo and the Time Log user picker use. */
    private fun resolveAssigneeAvatar(user: JiraUserService.JiraUser): ImageIcon? =
        JiraUserService.resolveAvatarIcon(user, tkPanel.cwDir()) {
            tkPanel.cardsPanel.revalidate()
            tkPanel.cardsPanel.repaint()
        }
}
