package com.khalibre.tools.devpanel.tickets

import com.intellij.openapi.application.ApplicationManager
import javax.swing.JComboBox
import javax.swing.SwingUtilities

class AssigneeUIService(private val tkPanel: TicketsPanel) {

    private val userComboService = JiraUserComboService(tkPanel.project, ::reRenderCards)

    private fun reRenderCards() {
        // Re-render so any already-built assignee dropdowns get populated with
        // the full user list instead of just their own ticket's current assignee.
        if (tkPanel.allLoadedTickets.isNotEmpty()) tkPanel.applySearch()
    }

    fun buildAssigneeCombo(ticket: JiraTicket): JComboBox<JiraUserService.JiraUser?> {
        return userComboService.buildAssigneeCombo(ticket).apply {
            addActionListener {
                val selected = selectedItem as? JiraUserService.JiraUser
                if (selected?.accountId == ticket.assigneeAccountId) return@addActionListener
                if (selected == null && ticket.assigneeAccountId == null) return@addActionListener
                onAssigneeChanged(ticket, selected)
            }
        }
    }

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
}
