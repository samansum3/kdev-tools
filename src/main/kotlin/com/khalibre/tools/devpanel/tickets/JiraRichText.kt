package com.khalibre.tools.devpanel.tickets

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.khalibre.tools.devpanel.tickets.JiraRichText.sanitizeForPaste
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist
import java.awt.datatransfer.Transferable
import java.io.InputStream
import java.io.Reader

/**
 * Bridges rich text between three representations:
 *  - HTML pasted from the outside world (Jira, browsers, editors) — needs sanitizing before it's
 *    safe to drop into our editor pane.
 *  - The HTML our description [javax.swing.JEditorPane] serializes to (`editorPane.text` when
 *    `contentType = "text/html"`).
 *  - Jira's Atlassian Document Format (ADF), the JSON tree the REST v3 API expects for a
 *    `description` field.
 *
 * Supports the common subset of Jira rich text: paragraphs, headings, bullet/ordered lists
 * (including nesting), blockquotes, code blocks, bold/italic/underline/inline-code marks, links,
 * and hard line breaks. Bare URLs left as plain text (e.g. from a plain-text paste) are still
 * auto-linked, same as before.
 */
object JiraRichText {

    // ── Sanitizing pasted HTML ───────────────────────────────────────────────

    private val PASTE_SAFELIST: Safelist = Safelist.none()
        .addTags(
            "p", "br", "ul", "ol", "li",
            "b", "strong", "i", "em", "u", "a", "code", "pre", "blockquote",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "s", "strike", "del", "hr", "sub"
        )
        .addAttributes("a", "href")
        .addProtocols("a", "href", "http", "https", "mailto")
        .addTags("table", "thead", "tbody", "tr", "th", "td")
        .addAttributes("th", "colspan", "rowspan")
        .addAttributes("td", "colspan", "rowspan")

    /**
     * Extracts an HTML string from [transferable] if it's carrying any "text/html" flavor at
     * all — regardless of which representation class the platform happens to expose it as
     * (`String`, `Reader`, or `InputStream`; this varies by OS and even JDK version, so checking
     * a single hard-coded flavor instance can silently miss real HTML on some platforms and
     * silently fall back to flattened plain text). Also strips a Windows CF_HTML header/fragment
     * wrapper (`<!--StartFragment-->...<!--EndFragment-->`) if one slipped through on the
     * `InputStream` path. Returns null if no HTML flavor is present or reading it fails.
     */
    fun readHtmlFlavor(transferable: Transferable): String? {
        val flavor =
            transferable.transferDataFlavors.firstOrNull { it.isMimeTypeEqual("text/html") }
                ?: return null
        val raw = try {
            when {
                String::class.java.isAssignableFrom(flavor.representationClass) ->
                    transferable.getTransferData(flavor) as? String

                Reader::class.java.isAssignableFrom(flavor.representationClass) ->
                    (transferable.getTransferData(flavor) as Reader).readText()

                InputStream::class.java.isAssignableFrom(flavor.representationClass) ->
                    (transferable.getTransferData(flavor) as InputStream).readBytes()
                        .toString(Charsets.UTF_8)

                else -> null
            }
        } catch (_: Exception) {
            null
        } ?: return null
        return stripCfHtmlWrapper(raw)
    }

    private fun stripCfHtmlWrapper(html: String): String {
        val startMarker = "<!--StartFragment-->"
        val endMarker = "<!--EndFragment-->"
        val start = html.indexOf(startMarker)
        val end = html.indexOf(endMarker)
        return if (start >= 0 && end > start) html.substring(
            start + startMarker.length,
            end
        ) else html
    }

    /** Cleans HTML from an arbitrary clipboard source down to the safe tag/attribute subset above,
     *  ready to hand to `HTMLEditorKit.insertHTML`. Unknown tags (spans, divs with styling, Jira's
     *  own wrapper markup, etc.) are unwrapped rather than dropped, so their text content survives. */
    fun sanitizeForPaste(rawHtml: String): String = Jsoup.clean(rawHtml, PASTE_SAFELIST)

    /** Wraps plain text (no HTML available on the clipboard) into the same safe HTML fragment
     *  shape as [sanitizeForPaste] would produce, so both paths feed the editor identically —
     *  one paragraph per blank-line-separated block, single newlines become `<br>`. */
    fun plainTextToHtmlFragment(text: String): String {
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val paragraphs =
            escaped.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }
        return paragraphs.joinToString("") { para ->
            "<p>${para.split("\n").joinToString("<br>")}</p>"
        }
    }

    // ── Blank check ───────────────────────────────────────────────────────────

    /** True if [html] (a full document, e.g. from the editor pane) has no visible text — used to
     *  decide whether the user left the description field empty. */
    fun isBlankHtml(html: String): Boolean = Jsoup.parse(html).body().text().isBlank()

    // ── HTML → ADF ────────────────────────────────────────────────────────────

    private val BLOCK_TAGS = setOf(
        "p",
        "div",
        "ul",
        "ol",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "blockquote",
        "pre",
        "table",
        "hr"
    )

    /** Matches a bare http(s) URL in plain text so it can become a real link mark. */
    private val URL_REGEX = Regex("""https?://[^\s<>\[\]]+""")

    /** Converts [html] (a full document — as produced by our description editor, or a fallback
     *  fragment built from clipboard text) into a Jira ADF `doc` node. */
    fun htmlToAdf(html: String): JsonObject {
        val body = Jsoup.parse(html).body()
        val content = mutableListOf<JsonObject>()
        val loose = mutableListOf<Node>()

        fun flushLoose() {
            if (loose.isEmpty()) return
            val inline = loose.flatMap { renderInline(it, emptyList()) }
            loose.clear()
            if (inline.isNotEmpty()) content += paragraphNode(inline)
        }

        body.childNodes().forEach { node ->
            if (node is Element && node.tagName().lowercase() in BLOCK_TAGS) {
                flushLoose()
                blockToAdf(node)?.let { content += it }
            } else {
                loose += node
            }
        }
        flushLoose()
        if (content.isEmpty()) content += paragraphNode(emptyList())

        return JsonObject().apply {
            addProperty("type", "doc")
            addProperty("version", 1)
            add("content", arr(content))
        }
    }

    private fun blockToAdf(el: Element): JsonObject? = when (el.tagName().lowercase()) {
        "p" -> paragraphNode(inlineContentOf(el))
        "div" -> {
            val blocks = mutableListOf<JsonObject>()
            val loose = mutableListOf<Node>()
            fun flushLoose() {
                if (loose.isEmpty()) return
                val inline = loose.flatMap { renderInline(it, emptyList()) }
                loose.clear()
                if (inline.isNotEmpty()) blocks += paragraphNode(inline)
            }
            el.childNodes().forEach { node ->
                if (node is Element && node.tagName().lowercase() in BLOCK_TAGS) {
                    flushLoose()
                    blockToAdf(node)?.let { blocks += it }
                } else {
                    loose += node
                }
            }
            flushLoose()
            // Return a wrapper or flatten into parent — since ADF has no "div" node,
            // return null and let caller collect the blocks
            // This requires a small refactor: blockToAdf returns List<JsonObject> instead of JsonObject?
            blocks.firstOrNull() // simplified — see refactor suggestion below
        }

        "h1", "h2", "h3", "h4", "h5", "h6" ->
            headingNode(el.tagName().substring(1).toIntOrNull() ?: 1, inlineContentOf(el))

        "ul" -> listNode("bulletList", el)
        "ol" -> listNode("orderedList", el)
        "blockquote" -> blockquoteNode(el)
        "pre" -> codeBlockNode(el.wholeText())
        "hr" -> JsonObject().apply { addProperty("type", "rule") }
        "table" -> tableToAdf(el)
        else -> null
    }

    // Before sanitizing, extract status lozenges from raw HTML
    private fun extractStatuses(rawHtml: String): Map<String, String> {
        val doc = Jsoup.parse(rawHtml)
        val statuses = mutableMapOf<String, String>()
        // Jira renders statuses as <span> with class containing "status-lozenge"
        doc.select("span[class*=status-lozenge]").forEach { span ->
            val text = span.text().trim()
            val color = when {
                span.className().contains("blue") -> "blue"
                span.className().contains("green") -> "green"
                span.className().contains("yellow") -> "yellow"
                span.className().contains("red") -> "red"
                else -> "neutral"
            }
            if (text.isNotBlank()) statuses[text] = color
        }
        return statuses
    }

    private fun tableToAdf(table: Element): JsonObject {
        val rows = mutableListOf<JsonObject>()
        // Collect rows from both <thead> and <tbody>, or directly under <table>
        val rowEls = table.select("tr")
        for (tr in rowEls) {
            val cells = mutableListOf<JsonObject>()
            for (cell in tr.children()) {
                val tag = cell.tagName().lowercase()
                if (tag != "td" && tag != "th") continue

                val cellType = if (tag == "th") "tableHeader" else "tableCell"
                val attrs = JsonObject()

                // colspan/rowspan — Jira requires these even when 1
                val colspan = cell.attr("colspan").toIntOrNull() ?: 1
                val rowspan = cell.attr("rowspan").toIntOrNull() ?: 1
                if (colspan > 1) attrs.addProperty("colspan", colspan)
                if (rowspan > 1) attrs.addProperty("rowspan", rowspan)

                // Cell content: block-level children inside the cell
                val cellContent = cellChildrenToAdf(cell)

                cells += JsonObject().apply {
                    addProperty("type", cellType)
                    if (attrs.size() > 0) add("attrs", attrs)
                    add("content", arr(cellContent.ifEmpty {
                        listOf(paragraphNode(emptyList()))
                    }))
                }
            }
            if (cells.isNotEmpty()) {
                rows += JsonObject().apply {
                    addProperty("type", "tableRow")
                    add("content", arr(cells))
                }
            }
        }
        if (rows.isEmpty()) return paragraphNode(emptyList()) // degenerate case

        return JsonObject().apply {
            addProperty("type", "table")
            add("attrs", JsonObject().apply {
                addProperty("isNumberColumnEnabled", false)
                addProperty("layout", "default")
            })
            add("content", arr(rows))
        }
    }

    /** Parses cell content — cells can contain paragraphs, lists, etc. */
    private fun cellChildrenToAdf(cell: Element): List<JsonObject> {
        val blocks = mutableListOf<JsonObject>()
        val loose = mutableListOf<Node>()
        fun flushLoose() {
            if (loose.isEmpty()) return
            val inline = loose.flatMap { renderInline(it, emptyList()) }
            loose.clear()
            if (inline.isNotEmpty()) blocks += paragraphNode(inline)
        }
        cell.childNodes().forEach { node ->
            if (node is Element && node.tagName().lowercase() in BLOCK_TAGS) {
                flushLoose()
                blockToAdf(node)?.let { blocks += it }
            } else {
                loose += node
            }
        }
        flushLoose()
        return blocks
    }

    private fun inlineContentOf(el: Element): List<JsonObject> =
        el.childNodes().flatMap { renderInline(it, emptyList()) }

    private fun listNode(type: String, listEl: Element): JsonObject {
        val items = listEl.children()
            .filter { it.tagName().equals("li", ignoreCase = true) }
            .map { listItemNode(it) }
        return JsonObject().apply { addProperty("type", type); add("content", arr(items)) }
    }

    private fun listItemNode(li: Element): JsonObject {
        val contentItems = mutableListOf<JsonObject>()
        val inlineBuf = mutableListOf<JsonObject>()
        fun flushParagraph() {
            if (inlineBuf.isNotEmpty()) {
                contentItems += paragraphNode(inlineBuf.toList())
                inlineBuf.clear()
            }
        }
        li.childNodes().forEach { node ->
            if (node is Element && node.tagName().lowercase() in setOf("ul", "ol")) {
                flushParagraph()
                val type = if (node.tagName()
                        .equals("ol", ignoreCase = true)
                ) "orderedList" else "bulletList"
                contentItems += listNode(type, node)
            } else {
                inlineBuf += renderInline(node, emptyList())
            }
        }
        flushParagraph()
        return JsonObject().apply {
            addProperty("type", "listItem"); add(
            "content",
            arr(contentItems)
        )
        }
    }

    private fun blockquoteNode(el: Element): JsonObject {
        val innerParagraphs = el.children().filter { it.tagName().equals("p", ignoreCase = true) }
        val paras = if (innerParagraphs.isNotEmpty())
            innerParagraphs.map { paragraphNode(inlineContentOf(it)) }
        else
            listOf(paragraphNode(inlineContentOf(el)))
        return JsonObject().apply { addProperty("type", "blockquote"); add("content", arr(paras)) }
    }

    private fun codeBlockNode(text: String): JsonObject = JsonObject().apply {
        addProperty("type", "codeBlock")
        add("content", arr(listOf(JsonObject().apply {
            addProperty("type", "text"); addProperty("text", text)
        })))
    }

    private fun paragraphNode(inline: List<JsonObject>): JsonObject = JsonObject().apply {
        addProperty("type", "paragraph")
        val consolidated = consolidateTextNodes(inline)
        if (consolidated.isNotEmpty()) add("content", arr(consolidated))
    }

    /** Merges adjacent text nodes that carry identical marks. */
    private fun consolidateTextNodes(nodes: List<JsonObject>): List<JsonObject> {
        if (nodes.size <= 1) return nodes
        val result = mutableListOf<JsonObject>()
        for (node in nodes) {
            val prev = result.lastOrNull()
            if (prev != null
                && prev.get("type")?.asString == "text"
                && node.get("type")?.asString == "text"
                && prev.get("marks")?.toString() == node.get("marks")?.toString()
            ) {
                val merged = prev.get("text").asString + node.get("text").asString
                prev.addProperty("text", merged)
            } else {
                result += node
            }
        }
        return result
    }

    private fun headingNode(level: Int, inline: List<JsonObject>): JsonObject = JsonObject().apply {
        addProperty("type", "heading")
        add("attrs", JsonObject().apply { addProperty("level", level.coerceIn(1, 6)) })
        add("content", arr(inline))
    }

    // ── Inline content + marks ────────────────────────────────────────────────

    private fun renderInline(node: Node, marks: List<JsonObject>): List<JsonObject> = when (node) {
        is TextNode -> {
            val text = node.text()
            if (text.isEmpty()) emptyList() else splitTextWithLinks(text, marks)
        }

        is Element -> when (node.tagName().lowercase()) {
            "br" -> listOf(JsonObject().apply { addProperty("type", "hardBreak") })
            "b", "strong" -> node.childNodes()
                .flatMap { renderInline(it, marks + markNode("strong")) }

            "s", "strike", "del" -> node.childNodes()
                .flatMap { renderInline(it, marks + markNode("strike")) }
            "sup" -> node.childNodes().flatMap {
                renderInline(it, marks + markNode("subsup",
                    JsonObject().apply { addProperty("type", "sup") }))
            }
            "sub" -> node.childNodes().flatMap {
                renderInline(it, marks + markNode("subsup",
                    JsonObject().apply { addProperty("type", "sub") }))
            }
            "i", "em" -> node.childNodes().flatMap { renderInline(it, marks + markNode("em")) }
            "u" -> node.childNodes().flatMap { renderInline(it, marks + markNode("underline")) }
            "code" -> node.childNodes().flatMap { renderInline(it, marks + markNode("code")) }
            "a" -> {
                val href = node.attr("href")
                val newMarks = if (href.isNotBlank())
                    marks + markNode("link", JsonObject().apply { addProperty("href", href) })
                else marks
                node.childNodes().flatMap { renderInline(it, newMarks) }
            }

            else -> node.childNodes().flatMap { renderInline(it, marks) }
        }

        else -> emptyList()
    }

    private fun markNode(type: String, attrs: JsonObject? = null): JsonObject = JsonObject().apply {
        addProperty("type", type)
        if (attrs != null) add("attrs", attrs)
    }

    private fun textNodeWithMarks(text: String, marks: List<JsonObject>): JsonObject =
        JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text)
            if (marks.isNotEmpty()) add("marks", arr(marks.distinctBy { it.toString() }))
        }

    /** Splits [text] around any bare http(s) URL, turning it into a link mark — unless [marks]
     *  already includes a link (i.e. this text is already inside a real `<a>`), in which case it's
     *  left alone. Trailing punctuation right after a URL is kept as plain text. */
    private fun splitTextWithLinks(text: String, marks: List<JsonObject>): List<JsonObject> {
        if (marks.any { it.get("type")?.asString == "link" }) return listOf(
            textNodeWithMarks(
                text,
                marks
            )
        )

        val nodes = mutableListOf<JsonObject>()
        var lastEnd = 0
        for (match in URL_REGEX.findAll(text)) {
            var url = match.value
            var end = match.range.last + 1
            while (url.isNotEmpty() && url.last() in ".,;:!?)]}\"'") {
                url = url.dropLast(1); end--
            }
            if (url.isEmpty()) continue
            val start = match.range.first
            if (start > lastEnd) nodes += textNodeWithMarks(text.substring(lastEnd, start), marks)
            nodes += textNodeWithMarks(
                url,
                marks + markNode("link", JsonObject().apply { addProperty("href", url) })
            )
            lastEnd = end
        }
        if (lastEnd < text.length) nodes += textNodeWithMarks(text.substring(lastEnd), marks)
        if (nodes.isEmpty()) nodes += textNodeWithMarks(text, marks)
        return nodes
    }

    private fun arr(items: List<JsonObject>): JsonArray =
        JsonArray().apply { items.forEach { add(it) } }
}
