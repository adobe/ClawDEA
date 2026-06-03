/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.chat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

class ChatBrowserRenderer(
    private val browser: JBCefBrowser,
    private val template: ChatHtmlTemplate,
    private val abortQuery: JBCefJSQuery,
    private val turnControlQuery: JBCefJSQuery,
    private val openDiffQuery: JBCefJSQuery,
    private val editActionQuery: JBCefJSQuery,
    private val healthQuery: JBCefJSQuery,
    private val openFileQuery: JBCefJSQuery,
    private val navigateQuery: JBCefJSQuery,
    private val permissionDecisionQuery: JBCefJSQuery,
    private val driftActionQuery: JBCefJSQuery,
    private val runSlashCommandQuery: JBCefJSQuery,
    private val wikiGitStateActionQuery: JBCefJSQuery,
) {
    var browserReady = false
        private set

    private val pendingHtml = ArrayDeque<String>(MAX_PENDING)

    init {
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                browserReady = true
                val bridgeScripts = template.buildBridgeScripts(
                    abortJs = abortQuery.inject("'abort'"),
                    turnControlJs = turnControlQuery.inject("action"),
                    openDiffJs = openDiffQuery.inject("toolId"),
                    editActionJs = editActionQuery.inject("arg"),
                    healthJs = healthQuery.inject("'ping'"),
                    openFileJs = openFileQuery.inject("path"),
                    navigateJs = navigateQuery.inject("ref"),
                    permissionDecisionJs = permissionDecisionQuery.inject("arg"),
                    driftActionJs = driftActionQuery.inject("action"),
                    runSlashCommandJs = runSlashCommandQuery.inject("slash"),
                    wikiGitStateActionJs = wikiGitStateActionQuery.inject("action"),
                )
                cefBrowser?.executeJavaScript(bridgeScripts, cefBrowser.url, 0)
                ApplicationManager.getApplication().invokeLater {
                    for (html in pendingHtml) {
                        executeAppend(html)
                    }
                    pendingHtml.clear()
                }
            }
        }, browser.cefBrowser)
    }

    fun loadPage(initialContent: String = "") {
        browserReady = false
        pendingHtml.clear()
        browser.loadHTML(template.buildPage(initialContent))
    }

    fun appendHtml(html: String) {
        if (!browserReady) {
            if (pendingHtml.size >= MAX_PENDING) pendingHtml.removeFirst()
            pendingHtml.addLast(html)
            return
        }
        executeAppend(html)
    }

    fun clearMessages() {
        if (!browserReady) return
        browser.cefBrowser.executeJavaScript(
            "document.getElementById('messages').innerHTML = '';",
            browser.cefBrowser.url, 0,
        )
    }

    /** Executes a raw JS snippet against the chat page. No-op if not ready. */
    fun executeJavaScript(js: String) {
        if (!browserReady) return
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    fun showThinkingIndicator() {
        if (!browserReady) return
        browser.cefBrowser.executeJavaScript("showThinking();", browser.cefBrowser.url, 0)
    }

    fun updateTurnControlButton(button: TurnControlButton) {
        if (!browserReady) return
        val state = when (button) {
            TurnControlButton.NONE -> "none"
            TurnControlButton.PAUSE -> "pause"
            TurnControlButton.STOP -> "stop"
        }
        browser.cefBrowser.executeJavaScript("setTurnControlButton('$state');", browser.cefBrowser.url, 0)
    }

    fun hideThinkingIndicator() {
        if (!browserReady) return
        browser.cefBrowser.executeJavaScript("hideThinking();", browser.cefBrowser.url, 0)
    }

    fun showPausedBanner() {
        if (!browserReady) return
        browser.cefBrowser.executeJavaScript("showPausedBanner();", browser.cefBrowser.url, 0)
    }

    fun hidePausedBanner() {
        if (!browserReady) return
        browser.cefBrowser.executeJavaScript("hidePausedBanner();", browser.cefBrowser.url, 0)
    }

    fun hideStopButton(toolUseId: String) {
        if (!browserReady) return
        val safeId = toolUseId.replace("'", "\\'").replace("\\", "\\\\")
        browser.cefBrowser.executeJavaScript(
            "var el = document.querySelector('[data-tool-id=\"$safeId\"] .tool-stop-btn'); if (el) el.style.display = 'none';",
            browser.cefBrowser.url, 0,
        )
    }

    fun hideAllStopButtons() {
        if (!browserReady) return
        browser.cefBrowser.executeJavaScript(
            "document.querySelectorAll('.tool-stop-btn').forEach(function(b){ b.style.display='none'; });",
            browser.cefBrowser.url, 0,
        )
    }

    fun injectElapsedTime(toolUseId: String, formattedElapsed: String) {
        if (!browserReady) return
        val safeId = toolUseId.replace("\\", "\\\\").replace("\"", "\\\"")
        browser.cefBrowser.executeJavaScript(
            """(function(){
                var block = document.querySelector('[data-tool-id="$safeId"]');
                if (!block) return;
                var header = block.querySelector('.tool-header');
                if (!header) return;
                var el = document.createElement('span');
                el.className = 'tool-elapsed';
                el.textContent = '$formattedElapsed';
                header.appendChild(el);
            })();""",
            browser.cefBrowser.url, 0,
        )
    }

    fun injectToolOutput(toolUseId: String, resultHtml: String) {
        if (!browserReady || resultHtml.isBlank()) return
        val escaped = escapeForJs(resultHtml)
        val safeId = toolUseId.replace("\\", "\\\\").replace("\"", "\\\"")
        browser.cefBrowser.executeJavaScript(
            """(function(){
                var block = document.querySelector('[data-tool-id="$safeId"]');
                if (!block) return;
                block.insertAdjacentHTML('beforeend', '$escaped');
            })();""",
            browser.cefBrowser.url, 0,
        )
    }

    /**
     * Append HTML to the tool block identified by [toolUseId]. Returns true
     * when the injection script ran. Used by the permission flow to render
     * the approval card *inside* the tool block instead of as a standalone
     * top-level card — so multi-tab projects route correctly by construction.
     *
     * Falls through to no-op if the browser is not yet ready or the block
     * does not exist (e.g. AskUserQuestion suppresses its own tool block).
     */
    fun injectToolAttachment(toolUseId: String, html: String) {
        if (!browserReady || html.isBlank()) return
        val escaped = escapeForJs(html)
        val safeId = toolUseId.replace("\\", "\\\\").replace("\"", "\\\"")
        browser.cefBrowser.executeJavaScript(
            """(function(){
                var block = document.querySelector('[data-tool-id="$safeId"]');
                if (!block) return;
                block.insertAdjacentHTML('beforeend', '$escaped');
            })();""",
            browser.cefBrowser.url, 0,
        )
    }

    /** Append [html] into the `.subagent-children` of the sub-agent card [parentId]. */
    fun appendIntoSubAgent(parentId: String, html: String) {
        if (!browserReady || html.isBlank()) return
        val safeId = parentId.replace("\\", "\\\\").replace("\"", "\\\"")
        browser.cefBrowser.executeJavaScript(
            "appendIntoSubAgent(\"$safeId\", '${escapeForJs(html)}');",
            browser.cefBrowser.url, 0,
        )
    }

    /** Update the live status line (e.g. step counter) of sub-agent card [parentId]. */
    fun updateSubAgentStatus(parentId: String, statusHtml: String) {
        if (!browserReady) return
        val safeId = parentId.replace("\\", "\\\\").replace("\"", "\\\"")
        browser.cefBrowser.executeJavaScript(
            "updateSubAgentStatus(\"$safeId\", '${escapeForJs(statusHtml)}');",
            browser.cefBrowser.url, 0,
        )
    }

    /** Collapse sub-agent card [parentId] and insert its final [summaryHtml]. */
    fun finalizeSubAgent(parentId: String, summaryHtml: String) {
        if (!browserReady) return
        val safeId = parentId.replace("\\", "\\\\").replace("\"", "\\\"")
        browser.cefBrowser.executeJavaScript(
            "finalizeSubAgent(\"$safeId\", '${escapeForJs(summaryHtml)}');",
            browser.cefBrowser.url, 0,
        )
    }

    fun updateEditLinkStatus(toolUseId: String, status: String, escapeHtml: (String) -> String) {
        if (!browserReady) return
        val safeId = toolUseId.replace("\\", "\\\\").replace("'", "\\'")
        val safeStatus = escapeHtml(status)
        val statusClass = when (status.lowercase()) {
            "accepted", "auto-accepted" -> "edit-status-accepted"
            "rejected" -> "edit-status-rejected"
            "modified" -> "edit-status-modified"
            "reviewing..." -> "edit-status-reviewing"
            "unavailable" -> "edit-status-unavailable"
            else -> "edit-status-pending"
        }
        val glyph = when (status.lowercase()) {
            "auto-accepted" -> "⚡"
            "accepted" -> "✓"
            "rejected" -> "✗"
            "modified" -> "✎"
            "reviewing..." -> "…"
            "unavailable" -> "—"
            else -> "•"
        }
        val labelText = "$glyph $safeStatus"
        browser.cefBrowser.executeJavaScript(
            """(function(){
                var el = document.querySelector('.edit-link[data-tool-id="$safeId"]');
                if (!el) return;
                var badge = el.querySelector('[class^="edit-status"]');
                var acceptBtn = el.querySelector('.edit-action-accept');
                var rejectBtn = el.querySelector('.edit-action-reject');
                if (acceptBtn) acceptBtn.remove();
                if (rejectBtn) rejectBtn.remove();
                if (badge) {
                    badge.className = '$statusClass';
                    badge.textContent = '$labelText';
                } else {
                    var span = document.createElement('span');
                    span.className = '$statusClass';
                    span.textContent = '$labelText';
                    el.appendChild(span);
                }
            })();""",
            browser.cefBrowser.url, 0,
        )
    }

    fun markEditLinkUnavailable(toolUseId: String, escapeHtml: (String) -> String) {
        updateEditLinkStatus(toolUseId, "Unavailable", escapeHtml)
        if (!browserReady) return
        val safeId = toolUseId.replace("\\", "\\\\").replace("'", "\\'")
        browser.cefBrowser.executeJavaScript(
            """(function(){
                var el = document.querySelector('.edit-link[data-tool-id="$safeId"]');
                if (!el) return;
                var path = el.querySelector('.edit-link-path');
                if (path) {
                    path.style.color = 'var(--overlay0)';
                    path.style.cursor = 'default';
                    path.removeAttribute('data-action');
                }
            })();""",
            browser.cefBrowser.url, 0,
        )
    }

    fun updateDriftBanner(html: String) {
        if (!browserReady) return
        browser.cefBrowser.executeJavaScript(
            "updateDriftBanner('${escapeForJs(html)}');",
            browser.cefBrowser.url, 0,
        )
    }

    fun updateWikiGitStateBanner(html: String) {
        if (!browserReady) return
        browser.cefBrowser.executeJavaScript(
            "updateWikiGitStateBanner('${escapeForJs(html)}');",
            browser.cefBrowser.url, 0,
        )
    }

    fun updateTaskWidget(html: String) {
        if (!browserReady) return
        if (html.isBlank()) return
        browser.cefBrowser.executeJavaScript(
            "updateTaskWidget('${escapeForJs(html)}');",
            browser.cefBrowser.url, 0,
        )
    }

    /**
     * Recover from a frozen JCEF compositor without disturbing the page DOM.
     *
     * Symptoms this addresses (issue #36):
     *  - After laptop sleep/wake, the page keeps receiving JS updates but the
     *    visible surface stops repainting. `loadHTML` papers over it for one
     *    frame because navigation forces a full repaint, but subsequent
     *    JS-driven appends never reach the screen again.
     *  - After plugging/unplugging an external monitor, the OSR backing
     *    surface keeps its old DPI scale and renders at "half resolution".
     *
     * The CEF native browser exposes three signals that, together, force the
     * rendering pipeline to re-acquire screen info and emit a fresh frame:
     *  - [org.cef.browser.CefBrowser.notifyScreenInfoChanged] re-reads the
     *    DPI/scale of the current GraphicsConfiguration.
     *  - [org.cef.browser.CefBrowser.wasResized] makes CEF treat the OSR
     *    surface as if it had just been resized, which restarts its paint
     *    loop on both OSR and windowed variants.
     *  - [org.cef.browser.CefBrowser.invalidate] requests a full repaint of
     *    the current viewport, which is what we ultimately need.
     *
     * On older JCEF builds where any of these methods may behave as no-ops,
     * the calls are wrapped in `runCatching` so a missing-method failure
     * never blocks the recovery sequence. The Swing pass at the end is
     * always run — it ensures the AWT peer notices any GraphicsConfiguration
     * change too.
     */
    fun forceRedraw() {
        val cef = browser.cefBrowser
        val component = browser.component
        runCatching { cef.notifyScreenInfoChanged() }
        val w = component.width
        val h = component.height
        if (w > 0 && h > 0) {
            runCatching { cef.wasResized(w, h) }
        }
        runCatching { cef.invalidate() }

        component.invalidate()
        component.parent?.revalidate()
        component.repaint()
    }

    companion object {
        private const val MAX_PENDING = 500

        fun escapeForJs(html: String): String = html
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("</", "<\\/")
    }

    private fun executeAppend(html: String) {
        browser.cefBrowser.executeJavaScript(
            "appendMessage('${escapeForJs(html)}');",
            browser.cefBrowser.url, 0,
        )
    }
}
