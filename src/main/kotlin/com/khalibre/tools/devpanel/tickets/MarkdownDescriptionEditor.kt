package com.khalibre.tools.devpanel.tickets

import com.google.gson.JsonObject
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import javax.swing.JPanel
import javax.swing.TransferHandler

/**
 * Ticket description editor: a plain Markdown source editor (bold as `**bold**`, lists as
 * `- item`, etc.) with a "smart paste" that converts pasted HTML (from Jira, a browser, ...)
 * into Markdown automatically.
 */
class MarkdownDescriptionEditor : JPanel(BorderLayout()) {

    private val textArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 8
        toolTipText = "Markdown supported (**bold**, - list, [link](url), ...) — " +
                "pasted formatted text is converted automatically"
    }

    init {
        preferredSize = Dimension(480, 160)
        add(JBScrollPane(textArea), BorderLayout.CENTER)
        installSmartPaste()
    }

    /** Intercepts paste to convert clipboard HTML (if present) into Markdown before inserting,
     *  so pasting from Jira/a browser keeps its formatting despite there being no real browser
     *  engine to render it with. Falls through to the default text paste for anything without an
     *  HTML flavor on the clipboard. */
    private fun installSmartPaste() {
        val defaultHandler = textArea.transferHandler
        textArea.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(DataFlavor.allHtmlFlavor) ||
                        (defaultHandler?.canImport(support) ?: super.canImport(support))
            }

            override fun importData(support: TransferSupport): Boolean {
                val html = extractHtml(support)
                if (html != null) {
                    val markdown = try {
                        MarkdownConverter.htmlToMarkdown(html)
                    } catch (e: Exception) {
                        null
                    }
                    if (markdown != null) {
                        textArea.replaceSelection(markdown)
                        return true
                    }
                }
                return defaultHandler?.importData(support) ?: super.importData(support)
            }
        }
    }

    private fun extractHtml(support: TransferHandler.TransferSupport): String? {
        if (!support.isDataFlavorSupported(DataFlavor.allHtmlFlavor)) return null
        return try {
            support.transferable.getTransferData(DataFlavor.allHtmlFlavor) as? String
        } catch (e: Exception) {
            null
        }
    }

    // ── Public API ────────────────────────────────────────────────────────
    // Same surface as the old TipTapDescriptionEditor so call sites don't need to change beyond
    // the class name.

    /** Renders the typed Markdown to HTML, or null if the editor is empty. */
    fun getContentHTML(): String? {
        val markdown = textArea.text
        if (markdown.isBlank()) return null
        return MarkdownConverter.markdownToHtml(markdown)
    }

    /** Content as an ADF JsonObject ready for Jira REST v3, via the existing HTML->ADF pipeline. */
    fun getContentAdf(): JsonObject? {
        val html = getContentHTML() ?: return null
        if (JiraRichText.isBlankHtml(html)) return null
        return try {
            JiraRichText.htmlToAdf(html)
        } catch (e: Exception) {
            println("[MarkdownEditor] ADF conversion error: ${e.message}")
            null
        }
    }

    fun isEmpty(): Boolean = textArea.text.isBlank()

    fun setEditable(editable: Boolean) {
        textArea.isEditable = editable
    }

    /** No-op — kept for API compatibility with the previous JCEF-based editor, which needed this
     *  to release native browser resources. Plain Swing components need no explicit disposal. */
    fun dispose() {
    }
}
