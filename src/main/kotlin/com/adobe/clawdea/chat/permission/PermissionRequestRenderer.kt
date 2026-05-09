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
package com.adobe.clawdea.chat.permission

import com.adobe.clawdea.chat.MessageRenderer

/**
 * Builds HTML fragments for permission approval cards in the chat transcript.
 *
 * Each card carries a `data-permission-id` attribute matching the
 * [PermissionRequest.requestId] so that the handler can flip it to a decided
 * state after Allow/Deny is clicked. Allow/Deny buttons use `data-action`
 * values `permission-allow` and `permission-deny`; the JCEF bridge translates
 * clicks into a `bridgePermissionDecision(...)` call.
 */
class PermissionRequestRenderer(private val messageRenderer: MessageRenderer) {

    fun renderCard(request: PermissionRequest): String {
        val id = messageRenderer.escapeHtml(request.requestId)
        val toolName = messageRenderer.escapeHtml(request.toolName)
        val summary = messageRenderer.escapeHtml(request.summary)
        return """
            <div class="permission-card" data-permission-id="$id">
                <div class="permission-header">
                    <span class="permission-icon">&#9888;</span>
                    <span class="permission-title">Approve tool: <code>$toolName</code></span>
                </div>
                <div class="permission-summary">$summary</div>
                <div class="permission-actions">
                    <button class="permission-allow-btn" data-action="permission-allow" data-permission-id="$id">Allow</button>
                    <button class="permission-always-btn" data-action="permission-always" data-permission-id="$id">Always allow...</button>
                    <button class="permission-deny-btn" data-action="permission-deny" data-permission-id="$id">Deny</button>
                    <div class="permission-scope-picker" style="display:none">
                        <div class="permission-scope-title">Always allow scope:</div>
                        <button data-action="permission-always-scope" data-scope="exact" data-permission-id="$id">This exact command/input</button>
                        <button data-action="permission-always-scope" data-scope="similar" data-permission-id="$id">Similar commands</button>
                        <button data-action="permission-always-scope" data-scope="tool" data-permission-id="$id">All calls to this tool</button>
                    </div>
                </div>
            </div>
        """.trimIndent()
    }

    /**
     * Renders a compact notice that a tool was auto-allowed by the current
     * "Allow all" setting. Non-interactive: no buttons, just a visual signal
     * so the user can see what ran and (in the tooltip) why it wasn't gated.
     */
    fun renderAutoAllowedNotice(request: PermissionRequest): String {
        val toolName = messageRenderer.escapeHtml(request.toolName)
        val summary = messageRenderer.escapeHtml(request.summary)
        val tooltip = messageRenderer.escapeHtml("Auto-allowed by Tool approval = Allow all")
        return """
            <div class="permission-card permission-card--auto-allowed" title="$tooltip">
                <div class="permission-header">
                    <span class="permission-icon">&#9889;</span>
                    <span class="permission-title">Auto-allowed: <code>$toolName</code></span>
                </div>
                <div class="permission-summary">$summary</div>
            </div>
        """.trimIndent()
    }

    /**
     * Returns JS that rewrites the card identified by [requestId] to show the
     * final decision ([decision] is "Allowed" or "Denied"). Strips the Allow
     * and Deny buttons and appends a status chip.
     */
    fun buildDecisionScript(requestId: String, decision: String): String {
        val safeId = escapeJavaScript(requestId)
        val safeDecision = escapeJavaScript(decision)
        val statusClass = statusClassFor(decision)
        return """(function(){
            var el = document.querySelector('.permission-card[data-permission-id="$safeId"]');
            if (!el) return;
            var actions = el.querySelector('.permission-actions');
            if (actions) actions.remove();
            var chip = document.createElement('div');
            chip.className = '$statusClass';
            chip.textContent = '$safeDecision';
            el.appendChild(chip);
            el.setAttribute('data-permission-decision', '$safeDecision');
        })();"""
    }

    fun buildScopePickerScript(requestId: String): String {
        val safeId = escapeJavaScript(requestId)
        return """(function(){
            var el = document.querySelector('.permission-card[data-permission-id="$safeId"]');
            if (!el) return;
            var picker = el.querySelector('.permission-scope-picker');
            if (picker) picker.style.display = 'flex';
        })();"""
    }

    fun buildWarningScript(requestId: String, message: String): String {
        val safeId = escapeJavaScript(requestId)
        val safeMessage = escapeJavaScript(message)
        return """(function(){
            var el = document.querySelector('.permission-card[data-permission-id="$safeId"]');
            if (!el) return;
            var warning = el.querySelector('.permission-warning');
            if (!warning) {
                warning = document.createElement('div');
                warning.className = 'permission-warning';
                el.appendChild(warning);
            }
            warning.textContent = "$safeMessage";
        })();"""
    }

    private fun escapeJavaScript(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun statusClassFor(decision: String): String {
        val loweredDecision = decision.lowercase()
        return when {
            "allow" in loweredDecision -> "permission-status-allowed"
            "den" in loweredDecision -> "permission-status-denied"
            else -> "permission-status-pending"
        }
    }
}
