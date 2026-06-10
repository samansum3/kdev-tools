package com.khalibre.link2command.devpanel.tickets

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.CompoundBorder

class TicketsPanel : JPanel(BorderLayout()) {

    // ── Filter inputs ─────────────────────────────────────────────────────
    private val parentKeysField = JBTextField().apply { toolTipText = "e.g. CW-36000, CW-36001" }
    private val fixVersionField = JBTextField().apply { toolTipText = "e.g. 13073" }

    // ── Toggle badges ─────────────────────────────────────────────────────
    private val badgeMyTasks     = makeBadge("my tasks",         true)
    private val badgeHideDone    = makeBadge("hide done",        true)
    private val badgeUnassigned  = makeBadge("unassigned",       false)
    private val badgeInProgress  = makeBadge("in progress",      false)
    private val badgeDeployedUat = makeBadge("deployed to UAT",  false)

    private val typeBadges = listOf(
        "Story", "Epic", "Improvement", "Task", "Sub-task",
        "Bug", "Defect", "Operations", "Test Report",
        "Release Procedure", "Translation Update"
    ).map { makeBadge(it, false) }

    // ── Cards area ────────────────────────────────────────────────────────
    private val cardsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private var currentUserEmail: String? = null

    init {
        border = JBUI.Borders.empty(8, 10)
        buildUi()
    }

    private fun buildUi() {
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // ── Row 1: parent tickets + fix version ───────────────────────────
        val inputRow = JPanel(GridLayout(1, 2, 6, 0)).apply {
            isOpaque = false; maximumSize = Dimension(Int.MAX_VALUE, 54)
        }

        fun inputBlock(label: String, field: JTextField): JPanel {
            val p = JPanel(BorderLayout(0, 2)).apply { isOpaque = false }
            p.add(JBLabel(label).apply { font = font.deriveFont(font.size - 1f) }, BorderLayout.NORTH)
            p.add(field, BorderLayout.CENTER)
            return p
        }
        inputRow.add(inputBlock("parent tickets", parentKeysField))
        inputRow.add(inputBlock("fix version", fixVersionField))
        topPanel.add(inputRow)
        topPanel.add(Box.createVerticalStrut(8))

        // ── Filters ───────────────────────────────────────────────────────
        topPanel.add(filterSection("owner", listOf(badgeMyTasks)))
        topPanel.add(Box.createVerticalStrut(4))
        topPanel.add(filterSection("status", listOf(badgeHideDone, badgeUnassigned, badgeInProgress, badgeDeployedUat)))
        topPanel.add(Box.createVerticalStrut(4))
        topPanel.add(filterSection("type", typeBadges))
        topPanel.add(Box.createVerticalStrut(6))

        topPanel.add(statusLabel)
        topPanel.add(Box.createVerticalStrut(4))

        add(topPanel, BorderLayout.NORTH)

        // ── Scrollable cards ──────────────────────────────────────────────
        val scroll = JBScrollPane(cardsPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)

        // apply filters on Enter in input fields
        val reloadOnEnter = { _: Any -> refresh() }
        parentKeysField.addActionListener(reloadOnEnter)
        fixVersionField.addActionListener(reloadOnEnter)
    }

    private fun filterSection(label: String, badges: List<JLabel>): JPanel {
        val p = JPanel(BorderLayout(0, 2)).apply { isOpaque = false }
        p.add(JBLabel(label).apply {
            font = font.deriveFont(font.size - 2f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }, BorderLayout.NORTH)

        val flow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        badges.forEach { flow.add(it) }
        p.add(flow, BorderLayout.CENTER)
        return p
    }

    fun refresh() {
        setStatus("Loading…")
        cardsPanel.removeAll()
        cardsPanel.revalidate()
        cardsPanel.repaint()

        val filters = buildFilters()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (currentUserEmail == null) currentUserEmail = JiraService.currentUserEmail()
                val jql = JiraService.buildJql(filters, currentUserEmail)
                val tickets = JiraService.searchTickets(jql)

                SwingUtilities.invokeLater {
                    cardsPanel.removeAll()
                    if (tickets.isEmpty()) {
                        cardsPanel.add(JBLabel("No tickets found").apply {
                            border = JBUI.Borders.empty(16, 4)
                            foreground = JBUI.CurrentTheme.Label.disabledForeground()
                        })
                    } else {
                        val countLabel = JBLabel("${tickets.size} ticket(s)").apply {
                            border = JBUI.Borders.emptyBottom(6)
                            font = font.deriveFont(font.size - 1f)
                            foreground = JBUI.CurrentTheme.Label.disabledForeground()
                        }
                        cardsPanel.add(countLabel)
                        tickets.forEach { ticket ->
                            cardsPanel.add(buildTicketCard(ticket))
                            cardsPanel.add(Box.createVerticalStrut(6))
                        }
                    }
                    cardsPanel.revalidate()
                    cardsPanel.repaint()
                    setStatus("")
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setStatus("Error: ${e.message?.take(80)}")
                    cardsPanel.removeAll()
                    cardsPanel.add(JBLabel("<html>${escHtml(e.message ?: "Unknown error")}</html>").apply {
                        border = JBUI.Borders.empty(12, 4)
                        foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    })
                    cardsPanel.revalidate()
                    cardsPanel.repaint()
                }
            }
        }
    }

    private fun buildFilters(): TicketFilters {
        val parentKeys = parentKeysField.text
            .split(",").map { it.trim().uppercase() }
            .filter { it.matches(Regex("[A-Z]+-[0-9]+")) }

        val fixVersion = fixVersionField.text.trim()

        val activeTypes = typeBadges.zip(listOf(
            "Story", "Epic", "Improvement", "Task", "Sub-task",
            "Bug", "Defect", "Operations", "Test Report",
            "Release Procedure", "Translation Update"
        )).filter { (badge, _) -> badge.getClientProperty("active") == true }
            .map { (_, name) -> name }
            .toSet()

        return TicketFilters(
            parentKeys = parentKeys,
            fixVersion = fixVersion,
            myTasks = badgeMyTasks.getClientProperty("active") == true,
            hideDone = badgeHideDone.getClientProperty("active") == true,
            unassigned = badgeUnassigned.getClientProperty("active") == true,
            inProgress = badgeInProgress.getClientProperty("active") == true,
            deployedUat = badgeDeployedUat.getClientProperty("active") == true,
            typeFilter = activeTypes
        )
    }

    private fun buildTicketCard(ticket: JiraTicket): JPanel {
        val card = JPanel(GridBagLayout()).apply {
            border = CompoundBorder(
                BorderFactory.createLineBorder(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, true),
                JBUI.Borders.empty(8, 10)
            )
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            alignmentX = LEFT_ALIGNMENT
        }
        val gbc = GridBagConstraints().apply {
            gridx = 0; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
            insets = JBUI.insets(1, 0, 1, 0)
        }

        // ── Key + status badge ────────────────────────────────────────────
        val topRow = JPanel(BorderLayout(6, 0)).apply { isOpaque = false }
        val keyLabel = JBLabel(ticket.key).apply {
            font = Font(Font.MONOSPACED, Font.BOLD, font.size - 1)
            foreground = Color(24, 95, 165)
        }
        topRow.add(keyLabel, BorderLayout.WEST)
        topRow.add(makeStatusBadge(ticket.status), BorderLayout.EAST)
        gbc.gridy = 0; card.add(topRow, gbc)

        // ── Summary ───────────────────────────────────────────────────────
        val titleLabel = JBLabel("<html>${escHtml(ticket.summary)}</html>").apply {
            font = font.deriveFont(Font.BOLD)
        }
        gbc.gridy = 1; card.add(titleLabel, gbc)

        // ── Type + priority + assignee badges ─────────────────────────────
        val metaPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { isOpaque = false }

        ticket.priority?.let { p ->
            metaPanel.add(makePriorityBadge(p))
        }
        ticket.issueType?.let { t ->
            metaPanel.add(makeTypeBadge(t))
        }
        val isMe = ticket.assigneeEmail != null && ticket.assigneeEmail == currentUserEmail
        val assigneeText = when {
            ticket.assigneeName == null -> "unassigned"
            isMe -> "● ${ticket.assigneeName}"
            else -> ticket.assigneeName
        }
        metaPanel.add(JBLabel(assigneeText).apply {
            font = font.deriveFont(font.size - 2f)
            foreground = if (isMe) Color(59, 109, 17) else JBUI.CurrentTheme.Label.disabledForeground()
        })
        gbc.gridy = 2; card.add(metaPanel, gbc)

        // ── Action buttons ────────────────────────────────────────────────
        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        addTicketActions(actionPanel, ticket)
        gbc.gridy = 3; card.add(actionPanel, gbc)

        return card
    }

    /** Action logic mirrors the Python render in subtasks script */
    private fun addTicketActions(panel: JPanel, ticket: JiraTicket) {
        val status = ticket.status
        val isMe = ticket.assigneeEmail != null && ticket.assigneeEmail == currentUserEmail
        val isUnassigned = ticket.assigneeName == null

        // pick: unassigned OR Failed QA
        if (isUnassigned || status == "Failed QA") {
            panel.add(makeActionButton("pick") { doTransition(ticket.key, "pick") })
        }

        // in-progress: author + (Deployed to UAT | Pending QA | Merged)
        if (isMe && status in listOf("Deployed to UAT", "Pending QA", "Merged")) {
            panel.add(makeActionButton("in progress") { doTransitionInProgress(ticket.key) })
        }

        // pr-open: author + In Progress
        if (isMe && status == "In Progress") {
            panel.add(makeActionButton("PR open") { doTransition(ticket.key, "PR Open") })
        }

        // merged: author + PR Open
        if (isMe && status == "PR Open") {
            panel.add(makeActionButton("merged") { doTransition(ticket.key, "Merged") })
        }

        // deployed-uat: Merged
        if (status == "Merged") {
            panel.add(makeActionButton("deployed UAT") { doTransitionDeployedUat(ticket.key) })
        }

        // pending-qa: Deployed to UAT
        if (status == "Deployed to UAT") {
            panel.add(makeActionButton("pending QA") { doTransitionPendingQa(ticket.key) })
        }

        // view: always
        panel.add(makeActionButton("view") { JiraService.openTicketInBrowser(ticket.key) })
    }

    private fun doTransition(key: String, targetStatus: String) {
        setStatus("Transitioning $key → $targetStatus…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = JiraService.transitionTicket(key, targetStatus)
            SwingUtilities.invokeLater {
                if (result.isSuccess) { setStatus("✓ $key → $targetStatus"); refresh() }
                else setStatus("✗ ${result.exceptionOrNull()?.message?.take(60)}")
            }
        }
    }

    private fun doTransitionInProgress(key: String) {
        setStatus("Transitioning $key → In Progress…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = JiraService.transitionToInProgress(key)
            SwingUtilities.invokeLater {
                if (result.isSuccess) { setStatus("✓ $key → In Progress"); refresh() }
                else setStatus("✗ ${result.exceptionOrNull()?.message?.take(60)}")
            }
        }
    }

    private fun doTransitionPendingQa(key: String) {
        setStatus("Transitioning $key → Pending QA…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = JiraService.transitionToPendingQa(key)
            SwingUtilities.invokeLater {
                if (result.isSuccess) { setStatus("✓ $key → Pending QA"); refresh() }
                else setStatus("✗ ${result.exceptionOrNull()?.message?.take(60)}")
            }
        }
    }

    private fun doTransitionDeployedUat(key: String) {
        setStatus("Transitioning $key → Deployed to UAT…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = JiraService.transitionToDeployedUat(key)
            SwingUtilities.invokeLater {
                if (result.isSuccess) { setStatus("✓ $key → Deployed to UAT"); refresh() }
                else setStatus("✗ ${result.exceptionOrNull()?.message?.take(60)}")
            }
        }
    }

    private fun setStatus(text: String) {
        statusLabel.text = text
    }

    // ── Badge / button factories ──────────────────────────────────────────

    companion object {
        fun makeBadge(text: String, initiallyActive: Boolean): JLabel {
            val label = JLabel(text)
            label.font = label.font.deriveFont(label.font.size - 2f)
            label.isOpaque = true
            label.putClientProperty("active", initiallyActive)
            label.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            applyBadgeStyle(label)

            label.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val current = label.getClientProperty("active") == true
                    label.putClientProperty("active", !current)
                    applyBadgeStyle(label)
                    label.repaint()
                }
                override fun mouseEntered(e: MouseEvent) {
                    label.putClientProperty("hovered", true)
                    applyBadgeStyle(label)
                }
                override fun mouseExited(e: MouseEvent) {
                    label.putClientProperty("hovered", false)
                    applyBadgeStyle(label)
                }
            })
            return label
        }

        private fun applyBadgeStyle(label: JLabel) {
            val active = label.getClientProperty("active") == true
            val hovered = label.getClientProperty("hovered") == true
            if (active) {
                // Active: solid blue background, white text — clearly ON
                label.background = Color(24, 95, 165)
                label.foreground = Color.WHITE
                label.border = JBUI.Borders.empty(3, 8)
            } else if (hovered) {
                // Hover on inactive: light blue tint
                label.background = Color(210, 228, 248)
                label.foreground = Color(24, 95, 165)
                label.border = JBUI.Borders.empty(3, 8)
            } else {
                // Inactive: muted grey — clearly OFF
                label.background = Color(225, 223, 218)
                label.foreground = Color(110, 108, 103)
                label.border = JBUI.Borders.empty(3, 8)
            }
        }
    }

    private fun makeStatusBadge(status: String): JLabel {
        val (bg, fg) = when (status) {
            "In Progress", "Defining AC", "PR Open" -> Pair(Color(230, 241, 251), Color(24, 95, 165))
            "Done", "Closed", "Resolved", "Merged", "Deployed to UAT", "Pending QA" -> Pair(Color(234, 243, 222), Color(59, 109, 17))
            "Blocked", "Failed QA" -> Pair(Color(252, 235, 235), Color(163, 45, 45))
            "Submitted for Review", "Pending AC Review" -> Pair(Color(250, 238, 218), Color(133, 79, 11))
            else -> Pair(Color(241, 239, 232), Color(95, 94, 90))
        }
        return JLabel(status).apply {
            isOpaque = true; background = bg; foreground = fg
            font = font.deriveFont(font.size - 2f)
            border = JBUI.Borders.empty(2, 6)
        }
    }

    private fun makePriorityBadge(priority: String): JLabel {
        val first = priority.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        return JLabel(first).apply {
            isOpaque = true
            background = Color(241, 239, 232)
            foreground = Color(95, 94, 90)
            font = Font(Font.MONOSPACED, Font.BOLD, font.size - 2)
            border = JBUI.Borders.empty(2, 5)
        }
    }

    private fun makeTypeBadge(type: String): JLabel {
        val (bg, fg) = when (type) {
            "Bug", "Defect" -> Pair(Color(252, 235, 235), Color(163, 45, 45))
            "Story" -> Pair(Color(234, 243, 222), Color(59, 109, 17))
            "Epic" -> Pair(Color(238, 237, 254), Color(60, 52, 137))
            "Improvement" -> Pair(Color(225, 245, 238), Color(15, 110, 86))
            "Operations" -> Pair(Color(238, 237, 254), Color(83, 74, 183))
            else -> Pair(Color(241, 239, 232), Color(95, 94, 90))
        }
        return JLabel(type).apply {
            isOpaque = true; background = bg; foreground = fg
            font = font.deriveFont(font.size - 2f)
            border = JBUI.Borders.empty(2, 6)
        }
    }

    private fun makeActionButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = font.deriveFont(font.size - 1f)
            isFocusPainted = false
            margin = JBUI.insets(2, 8)
            addActionListener { action() }
        }
    }

    private fun escHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
