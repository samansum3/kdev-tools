package com.khalibre.tools.devpanel.common

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel

object BadgeUtils {
    fun makeBadge(text: String, bg: Color, fg: Color): JLabel {
        return object : JLabel(text) {
            override fun getBackground(): Color = bg
            override fun getForeground(): Color = fg

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = background
                val w = width.toDouble()
                val h = height.toDouble()
                val r = JBUI.scale(4).toDouble()
                g2.fill(java.awt.geom.RoundRectangle2D.Double(0.0, 0.0, w, h, r, r))
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            font = font.deriveFont(font.size - 2f)
            border = JBUI.Borders.empty(2, 6)
        }
    }
}
