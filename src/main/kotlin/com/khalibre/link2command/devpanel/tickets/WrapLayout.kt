package com.khalibre.link2command.devpanel.tickets

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane

/**
 * A FlowLayout that wraps components to additional rows when the container
 * width is insufficient — unlike standard FlowLayout which clips.
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 4, vgap: Int = 4) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension =
        layoutSize(target, true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, false)

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val targetWidth = getTargetWidth(target)
            val insets = target.insets
            val hgap = hgap
            val vgap = vgap
            val maxWidth = targetWidth - insets.left - insets.right

            var width = 0
            var height = 0
            var rowWidth = 0
            var rowHeight = 0

            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rowWidth + d.width + hgap > maxWidth && rowWidth > 0) {
                    width = maxOf(width, rowWidth)
                    height += rowHeight + vgap
                    rowWidth = 0
                    rowHeight = 0
                }
                rowWidth += d.width + hgap
                rowHeight = maxOf(rowHeight, d.height)
            }
            width = maxOf(width, rowWidth)
            height += rowHeight
            return Dimension(
                width + insets.left + insets.right,
                height + insets.top + insets.bottom + vgap
            )
        }
    }

    private fun getTargetWidth(target: Container): Int {
        var targetWidth = target.size.width
        if (targetWidth == 0) {
            // Walk up to find a scroll pane or use parent width
            var p = target.parent
            while (p != null) {
                if (p is JScrollPane) {
                    targetWidth = p.viewport.width
                    break
                }
                if (p.size.width > 0) {
                    targetWidth = p.size.width
                    break
                }
                p = p.parent
            }
        }
        return if (targetWidth == 0) Int.MAX_VALUE else targetWidth
    }
}
