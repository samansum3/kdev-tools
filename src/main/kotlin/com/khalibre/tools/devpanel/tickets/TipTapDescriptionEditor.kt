package com.khalibre.tools.devpanel.tickets

import com.google.gson.JsonObject
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Rich text description editor using JCEF with a contenteditable div.
 * No external JS libraries needed — paste from Jira/browsers preserves
 * all formatting natively via the browser's clipboard handling.
 * Outputs sanitized HTML for conversion to ADF via JiraRichText.htmlToAdf().
 *
 * Place the companion HTML file at: src/main/resources/editor/rich-editor.html
 */
class TipTapDescriptionEditor : JPanel(BorderLayout()) {

    private var browser: JBCefBrowser? = null
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val placeholder = JLabel("Loading editor\u2026", JLabel.CENTER)

    private var readyQuery: JBCefJSQuery? = null
    private var contentQuery: JBCefJSQuery? = null
    private var emptyQuery: JBCefJSQuery? = null

    @Volatile
    private var editorReady = false

    private var pendingContent: CompletableFuture<String>? = null
    private var pendingEmpty: CompletableFuture<Boolean>? = null

    val isSupported: Boolean = JBCefApp.isSupported()

    init {
        preferredSize = Dimension(480, 160)
        add(cardPanel, BorderLayout.CENTER)

        if (!isSupported) {
            placeholder.text = "Rich editor not available"
            cardPanel.add(placeholder, "placeholder")
            cardLayout.show(cardPanel, "placeholder")
        } else {
            initBrowser()
        }
    }

    private fun initBrowser() {
        val b = JBCefBrowser()
        browser = b

        val rq = JBCefJSQuery.create(b)
        val cq = JBCefJSQuery.create(b)
        val eq = JBCefJSQuery.create(b)
        readyQuery = rq
        contentQuery = cq
        emptyQuery = eq

        // Both cards added immediately — browser component is in the Swing
        // hierarchy from the start so JCEF actually renders and executes JS.
        cardPanel.add(placeholder, "placeholder")
        cardPanel.add(b.component, "editor")
        cardLayout.show(cardPanel, "placeholder")

        rq.addHandler { _: String ->
            editorReady = true
            SwingUtilities.invokeLater {
                cardLayout.show(cardPanel, "editor")
            }
            JBCefJSQuery.Response("ok")
        }

        cq.addHandler { result: String ->
            pendingContent?.complete(result)
            JBCefJSQuery.Response("ok")
        }

        eq.addHandler { result: String ->
            pendingEmpty?.complete(result.trim() == "true")
            JBCefJSQuery.Response("ok")
        }

        // Forward JS console messages to IDE log for debugging
        b.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                browser: CefBrowser?,
                level: org.cef.CefSettings.LogSeverity?,
                message: String?,
                source: String?,
                line: Int
            ): Boolean {
                println("[RichEditor-JS] $message")
                return false
            }
        }, b.cefBrowser)

        // Inject the Kotlin↔JS bridge after the page finishes loading
        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                println("[RichEditor] onLoadEnd fired, status=$httpStatusCode")

                val bridgeJs = """
                    (function() {
                        console.log('[Editor] Injecting bridge');
                        window.notifyKotlinReady = function() {
                            ${rq.inject("'ready'")}
                        };
                        window.returnContentToKotlin = function(html) {
                            ${cq.inject("html")}
                        };
                        window.returnEmptyToKotlin = function(isEmpty) {
                            ${eq.inject("'' + isEmpty")}
                        };
                        console.log('[Editor] Bridge injected');
                        if (window.editorInit) {
                            window.editorInit();
                        } else {
                            console.error('[Editor] editorInit not found');
                        }
                    })();
                """.trimIndent()

                cefBrowser.executeJavaScript(bridgeJs, cefBrowser.url, 0)

                val isDark = UIUtil.isUnderDarcula()
                cefBrowser.executeJavaScript(
                    "if(window.setTheme) window.setTheme($isDark);",
                    cefBrowser.url, 0
                )
            }
        }, b.cefBrowser)

        // Load the editor HTML from resources
        val html = loadEditorHtml()
        b.loadHTML(html)
    }

    /**
     * Reads the editor HTML from the plugin resources directory.
     * Expected location: src/main/resources/editor/rich-editor.html
     */
    private fun loadEditorHtml(): String {
        return javaClass.getResourceAsStream("/editor/rich-editor.html")
            ?.bufferedReader()
            ?.readText()
            ?: error("rich-editor.html not found in resources at /editor/rich-editor.html")
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns sanitized HTML from the editor.
     * Call from a background thread — blocks until JS responds (up to 3s).
     */
    fun getContentHTML(): String? {
        if (!editorReady) return null
        pendingContent = CompletableFuture()
        browser?.cefBrowser?.executeJavaScript(
            "window.returnContentToKotlin(window.getEditorHTML());",
            browser?.cefBrowser?.url ?: "", 0
        )
        return try {
            pendingContent?.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
    }

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
     * Returns true if the editor has no visible content.
     * Call from a background thread.
     */
    fun isEmpty(): Boolean {
        if (!editorReady) return true
        pendingEmpty = CompletableFuture()
        browser?.cefBrowser?.executeJavaScript(
            "window.returnEmptyToKotlin(window.isEditorEmpty());",
            browser?.cefBrowser?.url ?: "", 0
        )
        return try {
            pendingEmpty?.get(3, TimeUnit.SECONDS) ?: true
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Enables or disables editing in the rich text editor.
     */
    fun setEditable(editable: Boolean) {
        if (!editorReady) return
        browser?.cefBrowser?.executeJavaScript(
            "document.getElementById('editor').contentEditable = $editable;",
            browser?.cefBrowser?.url ?: "", 0
        )
    }

    /**
     * Disposes all JCEF resources. Call when the dialog is closed.
     */
    fun dispose() {
        readyQuery?.dispose()
        contentQuery?.dispose()
        emptyQuery?.dispose()
        browser?.dispose()
    }
}
