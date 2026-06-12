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

    fun makeActionButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = font.deriveFont(font.size - 1f)
            isFocusPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(2, 6)
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
                    return ImageIcon(raw)
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
