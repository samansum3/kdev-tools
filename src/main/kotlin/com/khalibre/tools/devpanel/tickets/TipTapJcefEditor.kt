package com.khalibre.tools.devpanel.tickets

import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JPanel

/**
 * The actual JCEF-backed half of the rich text editor — deliberately split out of
 * [TipTapDescriptionEditor] rather than living behind an `if (JBCefApp.isSupported())` inside
 * that class.
 *
 * Why: Kotlin compiles lambdas via `invokedynamic` by default, which puts the lambda body as a
 * synthetic method directly on its *enclosing* class rather than a separate anonymous class. So a
 * lambda like `rq.addHandler { ... JBCefJSQuery.Response("ok") }` written inside
 * `TipTapDescriptionEditor` bakes a reference to `JBCefJSQuery.Response` straight into
 * `TipTapDescriptionEditor`'s own method table. AWT's `Component` constructor reflectively
 * enumerates *every* declared method on the object's exact runtime class (to check for a
 * `coalesceEvents` override) as part of its own `super()` call — before any of our code, including
 * an `isSupported` guard, ever runs. On a system without JCEF, that enumeration alone throws
 * `NoClassDefFoundError` for `JBCefJSQuery$Response`, crashing the dialog outright.
 *
 * Keeping all JCEF-referencing code (including its lambdas) in this separate class means this
 * class — and the JCEF types baked into its method table — is only classloaded when actually
 * instantiated, which [TipTapDescriptionEditor] only does inside its `isSupported` branch.
 */
internal class TipTapJcefEditor(private val onReady: () -> Unit) : JPanel(BorderLayout()) {

    private val browser = JBCefBrowser()

    private val readyQuery = JBCefJSQuery.create(browser)
    private val contentQuery = JBCefJSQuery.create(browser)
    private val emptyQuery = JBCefJSQuery.create(browser)

    @Volatile
    private var editorReady = false

    private var pendingContent: CompletableFuture<String>? = null
    private var pendingEmpty: CompletableFuture<Boolean>? = null

    init {
        add(browser.component, BorderLayout.CENTER)

        readyQuery.addHandler { _: String ->
            editorReady = true
            onReady()
            JBCefJSQuery.Response("ok")
        }

        contentQuery.addHandler { result: String ->
            pendingContent?.complete(result)
            JBCefJSQuery.Response("ok")
        }

        emptyQuery.addHandler { result: String ->
            pendingEmpty?.complete(result.trim() == "true")
            JBCefJSQuery.Response("ok")
        }

        // Forward JS console messages to IDE log for debugging
        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                cefBrowser: CefBrowser?,
                level: org.cef.CefSettings.LogSeverity?,
                message: String?,
                source: String?,
                line: Int
            ): Boolean {
                println("[RichEditor-JS] $message")
                return false
            }
        }, browser.cefBrowser)

        // Inject the Kotlin↔JS bridge after the page finishes loading
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                println("[RichEditor] onLoadEnd fired, status=$httpStatusCode")

                val bridgeJs = """
                    (function() {
                        console.log('[Editor] Injecting bridge');
                        window.notifyKotlinReady = function() {
                            ${readyQuery.inject("'ready'")}
                        };
                        window.returnContentToKotlin = function(html) {
                            ${contentQuery.inject("html")}
                        };
                        window.returnEmptyToKotlin = function(isEmpty) {
                            ${emptyQuery.inject("'' + isEmpty")}
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
        }, browser.cefBrowser)

        // Load the editor HTML from resources
        val html = loadEditorHtml()
        browser.loadHTML(html)
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

    /**
     * Returns sanitized HTML from the editor.
     * Call from a background thread — blocks until JS responds (up to 3s).
     */
    fun getContentHTML(): String? {
        if (!editorReady) return null
        pendingContent = CompletableFuture()
        browser.cefBrowser.executeJavaScript(
            "window.returnContentToKotlin(window.getEditorHTML());",
            browser.cefBrowser.url ?: "", 0
        )
        return try {
            pendingContent?.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
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
        browser.cefBrowser.executeJavaScript(
            "window.returnEmptyToKotlin(window.isEditorEmpty());",
            browser.cefBrowser.url ?: "", 0
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
        browser.cefBrowser.executeJavaScript(
            "document.getElementById('editor').contentEditable = $editable;",
            browser.cefBrowser.url ?: "", 0
        )
    }

    /**
     * Disposes all JCEF resources. Call when the dialog is closed.
     */
    fun dispose() {
        readyQuery.dispose()
        contentQuery.dispose()
        emptyQuery.dispose()
        browser.dispose()
    }
}
