package com.khalibre.link2command.devpanel.tickets

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.khalibre.link2command.devpanel.common.CardUtils
import com.khalibre.link2command.devpanel.common.ProjectPaths
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.SwingUtilities

/**
 * Shared type-icon loader: mem-cache → named disk cache (.git/cw/icons/<type>.png) → remote
 * fetch, with de-duplicated in-flight callbacks per type name. Extracted out of [TicketsPanel]
 * (which now just delegates to this) so other components — e.g. [NewTicketDialog]'s type
 * combo — resolve the exact same cached icons instead of maintaining a second cache.
 */
object JiraIconLoader {

    private val memCache = mutableMapOf<String, ImageIcon>()
    private val callbacks = mutableMapOf<String, MutableList<(ImageIcon) -> Unit>>()

    /** Synchronous peek — returns an already-resolved icon if one's in memory, else null. Used
     *  by synchronous Swing renderers (combo box cell rendering) that can't block on I/O. */
    fun cachedIconOrNull(typeName: String, size: Int): ImageIcon? =
        memCache[typeName]?.let { scaleIcon(it, size) }

    fun loadTypeIconAsync(
        project: Project,
        typeName: String,
        url: String?,
        size: Int,
        onLoaded: (ImageIcon) -> Unit
    ) {
        memCache[typeName]?.let { onLoaded(scaleIcon(it, size)); return }

        val queued = callbacks.getOrPut(typeName) { mutableListOf() }
        queued += onLoaded
        if (queued.size > 1) return // load already in flight

        ApplicationManager.getApplication().executeOnPooledThread {
            val cw = ProjectPaths.cwDir(project)
            var img: ImageIcon? = null

            if (cw != null) {
                val cacheFile = JiraMetaService.typeIconCacheFile(cw, typeName)
                if (cacheFile.exists()) img = loadAndScaleFile(cacheFile, size)
            }

            if (img == null && !url.isNullOrBlank()) {
                img =
                    CardUtils.fetchRemoteIcon(url, size, project.basePath?.let { File(it) }, false)
                if (img != null && cw != null) {
                    try {
                        val cacheFile = JiraMetaService.typeIconCacheFile(cw, typeName)
                        cacheFile.parentFile.mkdirs()
                        val raw = ImageIO.read(java.net.URL(url))
                        if (raw != null) ImageIO.write(raw, "png", cacheFile)
                    } catch (_: Exception) {
                    }
                }
            }

            val finalImg = img ?: return@executeOnPooledThread
            memCache[typeName] = finalImg
            SwingUtilities.invokeLater {
                callbacks.remove(typeName)?.forEach { cb -> cb(scaleIcon(finalImg, size)) }
            }
        }
    }

    private fun scaleIcon(icon: ImageIcon, size: Int): ImageIcon {
        if (icon.iconWidth == size && icon.iconHeight == size) return icon
        val buf = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = buf.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g.drawImage(icon.image.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null)
        g.dispose()
        return ImageIcon(buf)
    }

    private fun loadAndScaleFile(file: File, size: Int): ImageIcon? {
        return try {
            val raw = ImageIO.read(file) ?: return null
            val buf = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = buf.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.drawImage(raw.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null)
            g.dispose()
            ImageIcon(buf)
        } catch (_: Exception) {
            null
        }
    }
}
