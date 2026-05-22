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
 * Builds the interactive card for the `AskUserQuestion` tool. Single-select
 * questions render as radio groups; multi-select questions render as checkbox
 * groups. Each question carries its text in `data-question` on the inputs so
 * the JS submit handler can collect a `{question -> label(s)}` map.
 *
 * Submit posts `<requestId>:submit:<json-of-answers>` through the existing
 * permission JCEF bridge; cancel posts `<requestId>:deny`.
 */
class AskUserQuestionRenderer(private val messageRenderer: MessageRenderer) {

    fun renderCard(requestId: String, input: AskUserQuestionInput): String {
        val safeId = messageRenderer.escapeHtml(requestId)
        val questionsHtml = input.questions.withIndex().joinToString("\n") { (qIndex, q) ->
            renderQuestion(requestId, qIndex, q)
        }
        return """
            <div class="question-card" data-permission-id="$safeId">
                <div class="question-header">
                    <span class="question-icon">&#x2753;</span>
                    <span class="question-title">Claude is asking</span>
                </div>
                <div class="question-body">
$questionsHtml
                </div>
                <div class="question-actions">
                    <button class="question-submit-btn" data-action="question-submit" data-permission-id="$safeId">Submit</button>
                    <button class="question-cancel-btn" data-action="question-cancel" data-permission-id="$safeId">Skip</button>
                </div>
            </div>
        """.trimIndent()
    }

    private fun renderQuestion(requestId: String, qIndex: Int, q: AskUserQuestionInput.Question): String {
        val safeId = messageRenderer.escapeHtml(requestId)
        val safeQuestion = messageRenderer.escapeHtml(q.question)
        val safeQuestionAttr = messageRenderer.escapeHtml(q.question)
        val safeHeader = messageRenderer.escapeHtml(q.header)
        val groupName = "perm-$safeId-q$qIndex"
        val inputType = if (q.multiSelect) "checkbox" else "radio"
        val multiBadge = if (q.multiSelect) {
            """<span class="question-multi-badge">multi-select</span>"""
        } else ""
        val headerHtml = if (q.header.isNotBlank()) {
            """<span class="question-chip">$safeHeader</span>"""
        } else ""

        val optionsHtml = q.options.withIndex().joinToString("\n") { (oIndex, opt) ->
            val safeLabel = messageRenderer.escapeHtml(opt.label)
            val safeLabelAttr = messageRenderer.escapeHtml(opt.label)
            val safeDesc = messageRenderer.escapeHtml(opt.description)
            val descHtml = if (opt.description.isNotBlank()) {
                """<div class="question-option-desc">$safeDesc</div>"""
            } else ""
            val optionId = "$groupName-o$oIndex"
            """
                <label class="question-option" for="$optionId">
                    <input type="$inputType" id="$optionId" name="$groupName"
                           data-question="$safeQuestionAttr" data-label="$safeLabelAttr">
                    <div class="question-option-body">
                        <div class="question-option-label">$safeLabel</div>
                        $descHtml
                    </div>
                </label>
            """.trimIndent()
        }

        val freeformHtml = q.freeformInput?.let { ff ->
            val ffId = "$groupName-freeform"
            val safePrefill = messageRenderer.escapeHtml(ff.prefill)
            val safePlaceholder = messageRenderer.escapeHtml(ff.placeholder ?: ff.prefill)
            val labelHtml = ff.label?.takeIf { it.isNotBlank() }?.let { lbl ->
                """<label class="question-freeform-label" for="$ffId">${messageRenderer.escapeHtml(lbl)}</label>"""
            } ?: ""
            """
                <div class="question-freeform">
                    $labelHtml
                    <input type="text" id="$ffId" class="question-freeform-input"
                           data-question="$safeQuestionAttr" value="$safePrefill" placeholder="$safePlaceholder" />
                </div>
            """.trimIndent()
        } ?: ""

        return """
                <div class="question-block" data-question="$safeQuestionAttr">
                    <div class="question-block-header">
                        $headerHtml
                        $multiBadge
                    </div>
                    <div class="question-text">$safeQuestion</div>
                    <div class="question-options">
$optionsHtml
                    </div>
                    $freeformHtml
                </div>
        """.trimIndent()
    }

    /**
     * JS that rewrites the question card after a decision has been made.
     * Replaces the buttons + options with a static summary of the answers
     * (or just a "Skipped" chip when the user cancelled).
     */
    fun buildResolvedScript(requestId: String, answers: Map<String, String>, skipped: Boolean): String {
        val safeId = requestId.replace("\\", "\\\\").replace("\"", "\\\"")
        val statusClass = if (skipped) "question-status-skipped" else "question-status-answered"
        val statusText = if (skipped) "Skipped" else "Submitted"
        val safeStatus = statusText.replace("\\", "\\\\").replace("\"", "\\\"")
        val answerSummaryJs = if (skipped || answers.isEmpty()) {
            ""
        } else {
            val lines = answers.entries.joinToString(",") { (q, a) ->
                jsStringLiteral("• $q → $a")
            }
            """
            var summary = document.createElement('pre');
            summary.className = 'question-answers';
            summary.textContent = [$lines].join('\n');
            el.appendChild(summary);
            """.trimIndent()
        }
        return """(function(){
            var el = document.querySelector('.question-card[data-permission-id="$safeId"]');
            if (!el) return;
            var actions = el.querySelector('.question-actions');
            if (actions) actions.remove();
            var body = el.querySelector('.question-body');
            if (body) body.remove();
            var chip = document.createElement('div');
            chip.className = '$statusClass';
            chip.textContent = '$safeStatus';
            el.appendChild(chip);
            $answerSummaryJs
            el.setAttribute('data-permission-decision', '$safeStatus');
        })();"""
    }

    private fun jsStringLiteral(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("</", "<\\/")
        return "\"$escaped\""
    }
}
