package com.khalibre.tools.devpanel.tickets

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.JBUI
import com.khalibre.tools.devpanel.common.ProjectPaths
import com.khalibre.tools.devpanel.tickets.JiraUserService.JiraUser
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class JiraUserComboService(
    private val project: Project,
    private val onLoaded: (() -> Unit)? = null
) {

    private val cwDir get() = ProjectPaths.cwDir(project)
    private var currentUserAccountId: String? = null
    var jiraUsers: List<JiraUser> = emptyList()

    init {
        ApplicationManager.getApplication().executeOnPooledThread {
            val accountId = JiraUserService.currentAccountId(cwDir!!)
            val users = JiraUserService.loadUsers(cwDir!!)
            SwingUtilities.invokeLater {
                currentUserAccountId = accountId
                jiraUsers = users
                onLoaded?.invoke()
            }
        }
    }

    fun createJiraUserCombo(): JComboBox<JiraUser?> {
        val combo = buildJiraUserCombo()
        val (curFirst, rest) = jiraUsers.partition { it.accountId == currentUserAccountId }
        val ordered = curFirst + rest
        val model = DefaultComboBoxModel<JiraUser>()
        ordered.forEach { model.addElement(it) }
        combo.model = model
        return combo
    }

    fun buildAssigneeCombo(ticket: JiraTicket): JComboBox<JiraUser?> {
        val combo = buildJiraUserCombo()
        val (curFirst, rest) = jiraUsers.partition { it.accountId == currentUserAccountId }
        val ordered = curFirst + rest
        val model = DefaultComboBoxModel<JiraUser>()
        model.addElement(null) // Add Unassigned item
        ordered.forEach { model.addElement(it) }

        val ticketAssignee =
            jiraUsers.firstOrNull { it.accountId == ticket.assigneeAccountId }
        val selectedUser = ticketAssignee ?: ticket.assigneeAccountId?.let { accountId ->
            JiraUser(
                accountId,
                ticket.assigneeName ?: accountId,
                ticket.assigneeEmail,
                null
            ).also {
                model.addElement(it)
            }
        }

        combo.model = model
        combo.selectedItem = selectedUser
        return combo
    }

    private fun buildJiraUserCombo(): JComboBox<JiraUser?> {
        val hoverBg = JBUI.CurrentTheme.ActionButton.hoverBackground()

        val combo = object : ComboBox<JiraUser?>() {
            var isHovered = false

            override fun getInsets(): Insets {
                val hasAvatar = selectedItem is JiraUser
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
                val user = value as? JiraUser
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
                else if (user.accountId == currentUserAccountId)
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
        user: JiraUser,
        onLoaded: () -> Unit
    ): ImageIcon? =
        JiraUserService.resolveAvatarIcon(user, cwDir, onLoaded)
}