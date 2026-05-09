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
    private val openDiffQuery: JBCefJSQuery,
    private val editActionQuery: JBCefJSQuery,
    private val healthQuery: JBCefJSQuery,
    private val openFileQuery: JBCefJSQuery,
    private val navigateQuery: JBCefJSQuery,
    private val permissionDecisionQuery: JBCefJSQuery,
    private val driftActionQuery: JBCefJSQuery,
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
                    openDiffJs = openDiffQuery.inject("toolId"),
                    editActionJs = editActionQuery.inject("arg"),
                    healthJs = healthQuery.inject("'ping'"),
                    openFileJs = openFileQuery.inject("path"),
                    navigateJs = navigateQuery.inject("ref"),
                    permissionDecisionJs = permissionDecisionQuery.inject("arg"),
                    driftActionJs = driftActionQuery.inject("action"),
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
                    badge.textContent = '[$safeStatus]';
                } else {
                    var span = document.createElement('span');
                    span.className = '$statusClass';
                    span.textContent = '[$safeStatus]';
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

    fun updateTaskWidget(html: String) {
        if (!browserReady) return
        if (html.isBlank()) return
        browser.cefBrowser.executeJavaScript(
            "updateTaskWidget('${escapeForJs(html)}');",
            browser.cefBrowser.url, 0,
        )
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
