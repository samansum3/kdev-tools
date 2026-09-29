package com.khalibre.tools.devpanel.tickets

import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Markdown <-> HTML conversion for [MarkdownDescriptionEditor]. Keeps the existing Jira submit
 * pipeline (HTML -> ADF via [JiraRichText.htmlToAdf]) completely unchanged: users type or paste
 * Markdown, it gets rendered to HTML here, then handed off exactly as before.
 *
 * Markdown -> HTML uses `org.jetbrains:markdown-jvm` — the same library that powers WebStorm's
 * own bundled Markdown plugin preview. Pure Kotlin/JVM, no native dependencies, so unlike JCEF it
 * can't quietly vanish from a particular IDE build/packaging.
 */
object MarkdownConverter {

    private val flavour = CommonMarkFlavourDescriptor()

    fun markdownToHtml(markdown: String): String {
        val parser = MarkdownParser(flavour)
        val tree = parser.buildMarkdownTreeFromString(markdown)
        return HtmlGenerator(markdown, tree, flavour).generateHtml()
    }

    /**
     * Best-effort HTML -> Markdown, used for "smart paste": lets pasting from Jira/a browser
     * keep its formatting even without a real browser engine to fall back on. Covers the
     * formatting Jira descriptions actually use in practice (bold, italic, links, lists,
     * headings, code, blockquotes); anything unrecognized falls through as plain text rather
     * than being dropped.
     */
    fun htmlToMarkdown(html: String): String {
        val body = Jsoup.parse(html).body()
        val sb = StringBuilder()
        body.childNodes().forEach { renderNode(it, sb) }
        return sb.toString().trim('\n', ' ') + "\n"
    }

    private fun renderNode(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> sb.append(node.text())
            is Element -> renderElement(node, sb)
        }
    }

    private fun renderChildren(el: Element, sb: StringBuilder) {
        el.childNodes().forEach { renderNode(it, sb) }
    }

    private fun renderElement(el: Element, sb: StringBuilder) {
        when (el.tagName().lowercase()) {
            "strong", "b" -> {
                sb.append("**"); renderChildren(el, sb); sb.append("**")
            }

            "em", "i" -> {
                sb.append("*"); renderChildren(el, sb); sb.append("*")
            }

            "code" -> sb.append("`").append(el.text()).append("`")

            "pre" -> sb.append("\n```\n").append(el.text()).append("\n```\n")

            "a" -> {
                val href = el.attr("href")
                sb.append("[")
                renderChildren(el, sb)
                sb.append("](").append(href).append(")")
            }

            "br" -> sb.append("\n")

            "p", "div" -> {
                renderChildren(el, sb); sb.append("\n\n")
            }

            "h1" -> {
                sb.append("# "); renderChildren(el, sb); sb.append("\n\n")
            }

            "h2" -> {
                sb.append("## "); renderChildren(el, sb); sb.append("\n\n")
            }

            "h3" -> {
                sb.append("### "); renderChildren(el, sb); sb.append("\n\n")
            }

            "h4", "h5", "h6" -> {
                sb.append("#### "); renderChildren(el, sb); sb.append("\n\n")
            }

            "blockquote" -> {
                val inner = StringBuilder()
                renderChildren(el, inner)
                inner.toString().trim().lines().forEach { sb.append("> ").append(it).append("\n") }
                sb.append("\n")
            }

            "ul" -> {
                renderList(el, sb, ordered = false); sb.append("\n")
            }

            "ol" -> {
                renderList(el, sb, ordered = true); sb.append("\n")
            }

            else -> renderChildren(el, sb) // unrecognized wrapper — keep its text, drop the tag
        }
    }

    private fun renderList(listEl: Element, sb: StringBuilder, ordered: Boolean) {
        var index = 1
        listEl.children()
            .filter { it.tagName().equals("li", ignoreCase = true) }
            .forEach { li ->
                val marker = if (ordered) "${index++}. " else "- "
                val inner = StringBuilder()
                renderChildren(li, inner)
                sb.append(marker).append(inner.toString().trim()).append("\n")
            }
    }
}
