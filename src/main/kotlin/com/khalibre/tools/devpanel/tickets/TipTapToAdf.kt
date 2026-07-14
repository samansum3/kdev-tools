package com.khalibre.tools.devpanel.tickets

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Converts TipTap's ProseMirror JSON document to Jira's Atlassian Document Format (ADF).
 * Both are tree structures with nearly identical shapes — this is mostly a rename pass.
 */
object TipTapToAdf {

    /** Node type renames: TipTap name → ADF name */
    private val NODE_RENAMES = mapOf(
        "horizontalRule" to "rule",
        "bulletList" to "bulletList",
        "orderedList" to "orderedList",
        "listItem" to "listItem",
        "codeBlock" to "codeBlock",
        "blockquote" to "blockquote",
        "hardBreak" to "hardBreak",
        "table" to "table",
        "tableRow" to "tableRow",
        "tableCell" to "tableCell",
        "tableHeader" to "tableHeader"
    )

    /** Mark type renames: TipTap name → ADF name */
    private val MARK_RENAMES = mapOf(
        "bold" to "strong",
        "italic" to "em",
        "underline" to "underline",
        "strike" to "strike",
        "code" to "code",
        "link" to "link",
        "subscript" to "subsup",
        "superscript" to "subsup"
    )

    /**
     * Converts a TipTap ProseMirror document JSON to an ADF document JSON.
     * Input: `{ "type": "doc", "content": [...] }`
     * Output: `{ "type": "doc", "version": 1, "content": [...] }`
     */
    fun convert(tiptapDoc: JsonObject): JsonObject {
        val content = tiptapDoc.getAsJsonArray("content")
            ?.map { convertNode(it.asJsonObject) }
            ?.filter { it != null }
            ?.map { it!! }
            ?: emptyList()

        return JsonObject().apply {
            addProperty("type", "doc")
            addProperty("version", 1)
            add("content", toArray(content.ifEmpty { listOf(emptyParagraph()) }))
        }
    }

    private fun convertNode(node: JsonObject): JsonObject? {
        val type = node.get("type")?.asString ?: return null

        return when (type) {
            "doc" -> convert(node) // shouldn't happen at child level, but handle gracefully
            "text" -> convertTextNode(node)
            "paragraph" -> convertBlockNode("paragraph", node)
            "heading" -> convertHeadingNode(node)
            "bulletList", "orderedList" -> convertBlockNode(NODE_RENAMES[type] ?: type, node)
            "listItem" -> convertListItemNode(node)
            "blockquote" -> convertBlockNode("blockquote", node)
            "codeBlock" -> convertCodeBlockNode(node)
            "horizontalRule" -> JsonObject().apply { addProperty("type", "rule") }
            "hardBreak" -> JsonObject().apply { addProperty("type", "hardBreak") }
            "table" -> convertTableNode(node)
            "tableRow" -> convertBlockNode("tableRow", node)
            "tableCell" -> convertTableCellNode("tableCell", node)
            "tableHeader" -> convertTableCellNode("tableHeader", node)
            else -> convertBlockNode(NODE_RENAMES[type] ?: type, node)
        }
    }

    // ── Text + marks ──────────────────────────────────────────────────────

    private fun convertTextNode(node: JsonObject): JsonObject {
        val result = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", node.get("text")?.asString ?: "")
        }

        val marks = node.getAsJsonArray("marks")
        if (marks != null && marks.size() > 0) {
            val adfMarks = marks.map { convertMark(it.asJsonObject) }
            result.add("marks", toArray(adfMarks))
        }

        return result
    }

    private fun convertMark(mark: JsonObject): JsonObject {
        val type = mark.get("type")?.asString ?: "unknown"
        val adfType = MARK_RENAMES[type] ?: type

        return JsonObject().apply {
            addProperty("type", adfType)

            // Handle special mark attributes
            when (type) {
                "link" -> {
                    val attrs = mark.getAsJsonObject("attrs")
                    if (attrs != null) {
                        add("attrs", JsonObject().apply {
                            addProperty("href", attrs.get("href")?.asString ?: "")
                        })
                    }
                }
                "subscript" -> {
                    add("attrs", JsonObject().apply { addProperty("type", "sub") })
                }
                "superscript" -> {
                    add("attrs", JsonObject().apply { addProperty("type", "sup") })
                }
                else -> {
                    // Pass through any attrs that exist
                    val attrs = mark.getAsJsonObject("attrs")
                    if (attrs != null && attrs.size() > 0) {
                        add("attrs", attrs)
                    }
                }
            }
        }
    }

    // ── Block nodes ───────────────────────────────────────────────────────

    private fun convertBlockNode(adfType: String, node: JsonObject): JsonObject {
        val children = node.getAsJsonArray("content")
            ?.mapNotNull { convertNode(it.asJsonObject) }
            ?: emptyList()

        return JsonObject().apply {
            addProperty("type", adfType)
            if (children.isNotEmpty()) {
                add("content", toArray(children))
            }
        }
    }

    private fun convertHeadingNode(node: JsonObject): JsonObject {
        val level = node.getAsJsonObject("attrs")?.get("level")?.asInt ?: 1
        val children = node.getAsJsonArray("content")
            ?.mapNotNull { convertNode(it.asJsonObject) }
            ?: emptyList()

        return JsonObject().apply {
            addProperty("type", "heading")
            add("attrs", JsonObject().apply { addProperty("level", level.coerceIn(1, 6)) })
            if (children.isNotEmpty()) {
                add("content", toArray(children))
            }
        }
    }

    private fun convertListItemNode(node: JsonObject): JsonObject {
        val children = node.getAsJsonArray("content")
            ?.mapNotNull { convertNode(it.asJsonObject) }
            ?: emptyList()

        return JsonObject().apply {
            addProperty("type", "listItem")
            // ADF listItem must have at least one block child
            add("content", toArray(children.ifEmpty { listOf(emptyParagraph()) }))
        }
    }

    private fun convertCodeBlockNode(node: JsonObject): JsonObject {
        val children = node.getAsJsonArray("content")
            ?.mapNotNull { convertNode(it.asJsonObject) }
            ?: emptyList()

        val result = JsonObject().apply {
            addProperty("type", "codeBlock")
        }

        // Preserve language attribute if present
        val attrs = node.getAsJsonObject("attrs")
        val language = attrs?.get("language")?.asString
        if (!language.isNullOrBlank()) {
            result.add("attrs", JsonObject().apply { addProperty("language", language) })
        }

        if (children.isNotEmpty()) {
            result.add("content", toArray(children))
        }

        return result
    }

    // ── Table nodes ───────────────────────────────────────────────────────

    private fun convertTableNode(node: JsonObject): JsonObject {
        val rows = node.getAsJsonArray("content")
            ?.mapNotNull { convertNode(it.asJsonObject) }
            ?: emptyList()

        return JsonObject().apply {
            addProperty("type", "table")
            add("attrs", JsonObject().apply {
                addProperty("isNumberColumnEnabled", false)
                addProperty("layout", "default")
            })
            add("content", toArray(rows))
        }
    }

    private fun convertTableCellNode(adfType: String, node: JsonObject): JsonObject {
        val children = node.getAsJsonArray("content")
            ?.mapNotNull { convertNode(it.asJsonObject) }
            ?: emptyList()

        val result = JsonObject().apply {
            addProperty("type", adfType)
        }

        // Preserve colspan/rowspan
        val attrs = node.getAsJsonObject("attrs")
        if (attrs != null) {
            val cellAttrs = JsonObject()
            val colspan = attrs.get("colspan")?.asInt ?: 1
            val rowspan = attrs.get("rowspan")?.asInt ?: 1
            if (colspan > 1) cellAttrs.addProperty("colspan", colspan)
            if (rowspan > 1) cellAttrs.addProperty("rowspan", rowspan)
            if (cellAttrs.size() > 0) result.add("attrs", cellAttrs)
        }

        // ADF table cells must have at least one block child
        result.add("content", toArray(children.ifEmpty { listOf(emptyParagraph()) }))

        return result
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun emptyParagraph(): JsonObject = JsonObject().apply {
        addProperty("type", "paragraph")
    }

    private fun toArray(items: List<JsonObject>): JsonArray =
        JsonArray().apply { items.forEach { add(it) } }
}
