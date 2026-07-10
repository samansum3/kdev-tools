package com.khalibre.tools.devpanel.pr

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

data class UploadedImage(val width: Int, val height: Int, val url: String)

object ClipboardImage {
    private val IMAGE_URL_RE = Regex("""\((https://github\.com/user-attachments/assets/[^)]+)\)""")

    fun read(): BufferedImage? {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val contents = clipboard.getContents(null) ?: return null

        if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            try {
                val awtImage = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
                if (awtImage != null) {
                    val buffered = BufferedImage(
                        awtImage.getWidth(null),
                        awtImage.getHeight(null),
                        BufferedImage.TYPE_INT_ARGB
                    )
                    val g = buffered.createGraphics()
                    g.drawImage(awtImage, 0, 0, null)
                    g.dispose()
                    return buffered
                }
            } catch (_: Exception) { /* fall through to raw-stream flavors */
            }
        }

        val streamFlavor = contents.transferDataFlavors.firstOrNull {
            it.mimeType.startsWith("image/", ignoreCase = true) &&
                    it.representationClass == InputStream::class.java
        } ?: return null

        return try {
            (contents.getTransferData(streamFlavor) as? InputStream)?.use { ImageIO.read(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun hasImage(): Boolean = try {
        read() != null
    } catch (_: Exception) {
        false
    }

    /** Uploads whatever's on the clipboard via `gh image` */
    fun upload(sessionToken: String, workDir: File? = null): Result<UploadedImage> {
        val buffered = read() ?: return Result.failure(RuntimeException("No image in clipboard"))
        val tmpFile = File.createTempFile("clipboard-", ".png")
        return try {
            ImageIO.write(buffered, "png", tmpFile)
            val result = PrService.runCmd(
                listOf("gh", "image", tmpFile.absolutePath),
                workDir = workDir,                                    // ← add this
                env = mapOf("GH_SESSION_TOKEN" to sessionToken)
            )
            if (result.exitCode != 0)
                return Result.failure(
                    RuntimeException(
                        "gh image exited ${result.exitCode}: ${
                            result.stderr.ifBlank { result.stdout }.take(300)
                        }"
                    )
                )
            val url = IMAGE_URL_RE.find(result.stdout)?.groupValues?.get(1)
                ?: return Result.failure(
                    RuntimeException(
                        "gh image ran but no URL found in output: ${result.stdout.take(300)}"
                    )
                )
            Result.success(UploadedImage(buffered.width, buffered.height, url))
        } catch (e: Exception) {
            Result.failure(RuntimeException("Exception during upload: ${e.message}"))
        } finally {
            tmpFile.delete()
        }
    }
}
