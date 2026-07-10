package com.khalibre.link2command.devpanel.common

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.components.JBLabel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent

object SubTabUtils {
    fun buildAddButton(action: () -> Unit): JComponent {
        return createIconButton(
            AllIcons.General.Add,
            tooltip = "Add tab",
            scale = 1f
        ) {
            action()
        }.apply {
            border = JBUI.Borders.empty(2, 4, 6, 4)
        }
    }

    fun buildCloseButton(action: () -> Unit): JComponent {
        return createIconButton(
            AllIcons.Actions.Close,
            tooltip = "Close tab",
            scale = 1f
        ) {
            action()
        }
    }

    private fun createIconButton(
        icon: Icon,
        hoverIcon: Icon = icon,
        tooltip: String,
        scale: Float = 1f,
        action: () -> Unit
    ): JComponent {
        val normalIcon = IconLoader.getTransparentIcon(
            IconUtil.scale(icon, null, scale),
            0.5f
        )

        return JBLabel(normalIcon).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(1, 4)
            toolTipText = tooltip

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = action()

                override fun mouseEntered(e: MouseEvent) {
                    this@apply.icon = IconUtil.scale(hoverIcon, null, scale)
                }

                override fun mouseExited(e: MouseEvent) {
                    this@apply.icon = normalIcon
                }
            })
        }
    }
}
