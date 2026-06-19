package com.khalibre.link2command.devpanel.common

import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.pr.PrService
import com.khalibre.link2command.devpanel.tickets.JiraAuth
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.net.HttpURLConnection
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.border.CompoundBorder

object CardUtils {
    private val BUTTON_H_PADDING = JBUI.scale(4)      // right-side / text padding
    private val ARROW_LEFT_PADDING =
        JBUI.scale(8)     // left edge -> arrow, bit more breathing room
    private val ARROW_BADGE_GAP = JBUI.scale(4)        // arrow -> badge

    fun makeActionButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = font.deriveFont(font.size - 1f)
            isFocusPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(2, BUTTON_H_PADDING)
            addActionListener { action() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    isContentAreaFilled = true; repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    isContentAreaFilled = false; repaint()
                }
            })
        }
    }

    fun makeTransitionButton(
        targetStatus: String,
        tooltip: String? = null,
        bg: Color = Color(241, 239, 232),
        fg: Color = Color(95, 94, 90),
        arrowFg: Color = Color(120, 120, 120),
        action: () -> Unit
    ): JButton {
        // Derive the font up front so we can measure the arrow with the *real* font,
        // before the button even exists.
        val baseFont = JBUI.Fonts.label().deriveFont(JBUI.Fonts.label().size - 1f)
        val arrowWidth = Toolkit.getDefaultToolkit()
            .getFontMetrics(baseFont).stringWidth("→") // rough pre-measure; refined below

        val btn = object : JButton(targetStatus) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                )

                val in_ = insets
                g2.color = when {
                    model.isPressed -> bg.darker().darker()
                    model.isRollover -> bg.darker()
                    else -> bg
                }
                val arcRadius = 4 // tweak to taste — try 6-10 for a subtle pill-ish look

                g2.fillRoundRect(
                    in_.left + arrowWidth + 10,
                    7,
                    width - in_.left - in_.right - arrowWidth - 15,
                    height - 14,
                    arcRadius,
                    arcRadius
                )

                g2.color = arrowFg
                g2.font = font
                val fm = g2.fontMetrics
                val arrowY = (height + fm.ascent - fm.descent) / 2
                g2.drawString("→", ARROW_LEFT_PADDING, arrowY)

                g2.dispose()
                super.paintComponent(g)
            }
        }

        btn.font = baseFont
        btn.foreground = fg
        btn.isOpaque = false
        btn.isContentAreaFilled = false
        btn.isFocusPainted = false
        btn.isRolloverEnabled = true

        // Reserve left space for [padding][arrow][gap], right side just normal padding.
        val fm = btn.getFontMetrics(btn.font)
        val leftSpace = ARROW_LEFT_PADDING + fm.stringWidth("→") + ARROW_BADGE_GAP
        btn.margin = JBUI.insets(2, leftSpace, 2, BUTTON_H_PADDING + 4)
        val pref = btn.preferredSize
        btn.preferredSize = Dimension(pref.width + 4, pref.height)

        if (tooltip != null) btn.toolTipText = tooltip
        btn.addActionListener { action() }
        return btn
    }

    // Per-group selection state — key is a group tag (e.g. "tickets", "pr"), value is the selected card key
    private val selectedCards = mutableMapOf<String, JPanel?>()
    private val selectedKeys = mutableMapOf<String, String?>()

    fun makeCard(ticketKey: String? = null, group: String = "default"): JPanel {
        val normalBorder = CompoundBorder(
            javax.swing.BorderFactory.createLineBorder(
                JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, true
            ),
            JBUI.Borders.empty(8, 10)
        )
        val selectedBorder = CompoundBorder(
            javax.swing.BorderFactory.createLineBorder(Color(24, 95, 165), 2, true),
            JBUI.Borders.empty(7, 9)  // 1px less to compensate for thicker border
        )
        return JPanel(GridBagLayout()).apply {
            putClientProperty("ticketKey", ticketKey)
            putClientProperty("group", group)
            border =
                if (ticketKey != null && ticketKey == selectedKeys[group]) selectedBorder else normalBorder
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val g = getClientProperty("group") as? String ?: "default"
                    // Deselect previously selected card in same group
                    selectedCards[g]?.let { prev ->
                        if (prev !== this@apply) {
                            prev.border = normalBorder
                            prev.repaint()
                        }
                    }
                    // Select this card
                    selectedCards[g] = this@apply
                    selectedKeys[g] = getClientProperty("ticketKey") as? String
                    border = selectedBorder
                    repaint()
                }
            })
        }
    }

    fun cardGbc(): GridBagConstraints {
        return GridBagConstraints().apply {
            gridx = 0; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
            insets = Insets(0, 0, 2, 0)
        }
    }

    fun escHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun fetchRemoteIcon(
        url: String,
        size: Int,
        workDir: File? = null,
        useCache: Boolean = false
    ): javax.swing.ImageIcon? {
        val cacheFile = if (useCache) iconCacheFile(url, workDir) else null

        if (cacheFile?.exists() == true) {
            try {
                val raw = ImageIO.read(cacheFile)
                if (raw != null) {
                    val img = java.awt.image.BufferedImage(
                        size,
                        size,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB
                    )
                    val g = img.createGraphics()
                    g.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                    )
                    g.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR
                    )
                    g.drawImage(raw.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null)
                    g.dispose()
                    return ImageIcon(img)
                }
                cacheFile.delete()
            } catch (_: Exception) {
                cacheFile.delete()
            }
        }

        return try {
            val conn = java.net.URL(url).openConnection() as HttpURLConnection
            JiraAuth.apply(conn)

            val raw = javax.imageio.ImageIO.read(conn.inputStream) ?: return null

            if (cacheFile != null) {
                try {
                    ImageIO.write(raw, "png", cacheFile)
                } catch (_: Exception) {
                }
            }

            val img = java.awt.image.BufferedImage(
                size,
                size,
                java.awt.image.BufferedImage.TYPE_INT_ARGB
            )

            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.drawImage(raw.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null)
            g.dispose()

            javax.swing.ImageIcon(img)
        } catch (e: Exception) {
            null
        }
    }

    private fun iconCacheFile(url: String, workDir: File? = null): File? {
        val gitDir = findGitDir(workDir) ?: return null

        val hash = java.security.MessageDigest
            .getInstance("SHA-1")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val dir = File(gitDir, "cw/icons")
        dir.mkdirs()

        return File(dir, "$hash.png")
    }

    private fun findGitDir(workDir: File? = null): File? {
        val result = PrService.runCmd(
            listOf("git", "rev-parse", "--show-toplevel"),
            workDir
        )

        if (result.exitCode != 0) return null

        val root = File(result.stdout.trim())

        val git = File(root, ".git")

        return when {
            git.isDirectory -> git.canonicalFile

            git.isFile -> {
                // worktree / submodule case
                val content = git.readText().trim()
                if (content.startsWith("gitdir:")) {
                    File(content.removePrefix("gitdir:").trim()).canonicalFile
                } else null
            }

            else -> null
        }
    }
}
