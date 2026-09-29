package com.khalibre.tools.devpanel.tickets

import com.google.gson.JsonObject
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Rich text description editor using JCEF with a contenteditable div — falls back to a plain
 * "not available" placeholder when JCEF isn't supported (or isn't even present at all — see
 * below) on this system. No external JS libraries needed — paste from Jira/browsers preserves
 * all formatting natively via the browser's clipboard handling. Outputs sanitized HTML for
 * conversion to ADF via JiraRichText.htmlToAdf().
 *
 * This class must never contain a *direct* reference — including inside a lambda, and including
 * a single static method call sitting outside any `if` — to any `com.intellij.ui.jcef.*` type.
 * Two separate failure modes led to that rule:
 *
 *  1. Kotlin compiles lambdas via `invokedynamic` by default, which bakes the lambda body as a
 *     synthetic method directly onto its *enclosing* class. AWT's `Component` constructor
 *     reflectively enumerates every declared method on the object's exact runtime class as part
 *     of its own `super()` call, before any of our code runs — so a JCEF-typed lambda anywhere
 *     in this class crashes construction outright on a system without the `JBCefJSQuery$Response`
 *     class, even if that lambda is never invoked. (This is why the actual JCEF implementation
 *     lives in the separate [TipTapJcefEditor] class instead.)
 *  2. On some builds — apparently including at least one JetBrains snap package — the entire
 *     `com.intellij.ui.jcef` package is simply absent, not just one nested class. In that case
 *     even `JBCefApp.isSupported()` itself, called plainly in an `init`/property initializer,
 *     is an unconditional `INVOKESTATIC` that throws `NoClassDefFoundError` the instant it runs —
 *     no `if` placement avoids that, since the call itself is what's missing its target, not a
 *     type merely referenced in an unreachable branch. [detectJcefSupport] sidesteps this by
 *     checking for `JBCefApp` via pure reflection instead of a compiled-in reference.
 *
 * Place the companion HTML file at: src/main/resources/editor/rich-editor.html
 */
class TipTapDescriptionEditor : JPanel(BorderLayout()) {

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val placeholder = JLabel("Loading editor\u2026", JLabel.CENTER)

    private var jcefEditor: TipTapJcefEditor? = null

    val isSupported: Boolean = detectJcefSupport()

    init {
        preferredSize = Dimension(480, 160)
        add(cardPanel, BorderLayout.CENTER)

        if (!isSupported) {
            placeholder.text = "Rich editor not available"
            cardPanel.add(placeholder, "placeholder")
            cardLayout.show(cardPanel, "placeholder")
        } else {
            cardPanel.add(placeholder, "placeholder")
            cardLayout.show(cardPanel, "placeholder")

            // Both cards added immediately — browser component is in the Swing hierarchy from
            // the start so JCEF actually renders and executes JS.
            try {
                val editor = TipTapJcefEditor(onReady = {
                    SwingUtilities.invokeLater { cardLayout.show(cardPanel, "editor") }
                })
                jcefEditor = editor
                cardPanel.add(editor, "editor")
            } catch (e: Throwable) {
                // Belt-and-suspenders: detectJcefSupport() said yes, but construction itself
                // still failed for some other platform-specific reason. Fall back rather than
                // crash the whole dialog.
                placeholder.text = "Rich editor not available"
            }
        }
    }

    /**
     * Checks for `com.intellij.ui.jcef.JBCefApp.isSupported()` purely via reflection — see the
     * class doc for why a direct static call isn't safe here. `Class.forName`/`Method.invoke`
     * failures surface as catchable exceptions instead of classloading-time crashes.
     */
    private fun detectJcefSupport(): Boolean {
        return try {
            val cls = Class.forName("com.intellij.ui.jcef.JBCefApp")
            val method = cls.getMethod("isSupported")
            (method.invoke(null) as? Boolean) ?: false
        } catch (e: Throwable) {
            false
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns sanitized HTML from the editor, or null when JCEF isn't available or the editor
     * hasn't finished loading yet. Call from a background thread — blocks until JS responds.
     */
    fun getContentHTML(): String? = jcefEditor?.getContentHTML()

    /**
     * Returns the content as an ADF JsonObject ready for Jira REST v3.
     * Call from a background thread. Uses existing JiraRichText.htmlToAdf().
     */
    fun getContentAdf(): JsonObject? {
        val html = getContentHTML() ?: return null
        if (JiraRichText.isBlankHtml(html)) return null
        return try {
            JiraRichText.htmlToAdf(html)
        } catch (e: Exception) {
            println("[RichEditor] ADF conversion error: ${e.message}")
            null
        }
    }

    /**
     * Returns true if the editor has no visible content (also true when JCEF isn't available,
     * since there's nothing to submit either way). Call from a background thread.
     */
    fun isEmpty(): Boolean = jcefEditor?.isEmpty() ?: true

    /**
     * Enables or disables editing in the rich text editor. No-op when JCEF isn't available.
     */
    fun setEditable(editable: Boolean) {
        jcefEditor?.setEditable(editable)
    }

    /**
     * Disposes all JCEF resources. Call when the dialog is closed.
     */
    fun dispose() {
        jcefEditor?.dispose()
    }
}
