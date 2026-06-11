package com.khalibre.link2command.devpanel.common

import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.CompoundBorder

object CardUtils {

    fun makeActionButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = font.deriveFont(font.size - 1f)
            isFocusPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(2, 6)
            addActionListener { action() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { isContentAreaFilled = true; repaint() }
                override fun mouseExited(e: MouseEvent)  { isContentAreaFilled = false; repaint() }
            })
        }
    }

    fun makeTransitionButton(targetStatus: String, action: () -> Unit): JButton =
        makeActionButton("→ $targetStatus", action)

    fun makeCard(): JPanel {
        return JPanel(GridBagLayout()).apply {
            border = CompoundBorder(
                javax.swing.BorderFactory.createLineBorder(
                    JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, true
                ),
                JBUI.Borders.empty(8, 10)
            )
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    fun cardGbc(): GridBagConstraints {
        return GridBagConstraints().apply {
            gridx = 0; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
            insets = Insets(0, 0, 2, 0)
        }
    }

    fun escHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun fetchRemoteIcon(url: String, size: Int): javax.swing.ImageIcon? {
        return try {
            val raw = javax.imageio.ImageIO.read(java.net.URL(url)) ?: return null
            val img = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(raw.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null)
            g.dispose()
            javax.swing.ImageIcon(img)
        } catch (e: Exception) { null }
    }
}
