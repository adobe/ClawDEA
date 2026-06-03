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

class ChatHtmlTemplate {

    private val baseHtml: String = loadResource("/chat/chat-page.html")

    fun buildPage(initialContent: String = ""): String =
        baseHtml.replace("{{INITIAL_CONTENT}}", initialContent)

    fun buildBridgeScripts(
        abortJs: String,
        turnControlJs: String,
        openDiffJs: String,
        editActionJs: String,
        healthJs: String,
        openFileJs: String,
        navigateJs: String,
        permissionDecisionJs: String,
        driftActionJs: String,
        runSlashCommandJs: String,
        wikiGitStateActionJs: String,
    ): String = """
        window.bridgeStopTool = function() { $abortJs };
        window.bridgeTurnControl = function(action) { $turnControlJs };
        window.bridgeOpenDiff = function(toolId) { $openDiffJs };
        window.bridgeEditAction = function(arg) { $editActionJs };
        window.bridgeHealthPing = function() { $healthJs };
        window.bridgeOpenFile = function(path) { $openFileJs };
        window.bridgeNavigate = function(ref) { $navigateJs };
        window.bridgePermissionDecision = function(arg) { $permissionDecisionJs };
        window.bridgeDriftAction = function(action) { $driftActionJs };
        window.bridgeRunSlashCommand = function(slash) { $runSlashCommandJs };
        window.bridgeWikiGitStateAction = function(action) { $wikiGitStateActionJs };
        window.collectQuestionAnswers = function(card) {
            // Walk every radio/checkbox in the card; group multi-select labels
            // under their question text. Also collect any freeform text inputs
            // (class="question-freeform-input") into a sibling freeforms map.
            // Returns { answers: { "<question>": "<label>" | "<l1>, <l2>" },
            //           freeforms: { "<question>": "<text>" } }.
            var answers = {};
            var inputs = card.querySelectorAll('input[type="radio"], input[type="checkbox"]');
            for (var i = 0; i < inputs.length; i++) {
                var inp = inputs[i];
                if (!inp.checked) continue;
                var q = inp.getAttribute('data-question') || '';
                var l = inp.getAttribute('data-label') || '';
                if (!q || !l) continue;
                if (answers[q]) {
                    answers[q] = answers[q] + ', ' + l;
                } else {
                    answers[q] = l;
                }
            }
            var freeforms = {};
            var textInputs = card.querySelectorAll('input.question-freeform-input[type="text"]');
            for (var j = 0; j < textInputs.length; j++) {
                var t = textInputs[j];
                var qq = t.getAttribute('data-question') || '';
                if (!qq) continue;
                freeforms[qq] = t.value || '';
            }
            return { answers: answers, freeforms: freeforms };
        };
        document.addEventListener('click', function(e) {
            var el = e.target.closest('[data-action]');
            if (!el) return;
            var action = el.getAttribute('data-action');
            var toolId = el.getAttribute('data-tool-id');
            var permissionId = el.getAttribute('data-permission-id');
            switch (action) {
                case 'open-diff': bridgeOpenDiff(toolId); break;
                case 'edit-accept': bridgeEditAction(toolId + ':accept'); break;
                case 'edit-reject': bridgeEditAction(toolId + ':reject'); break;
                case 'stop-tool': bridgeStopTool(); break;
                case 'turn-pause': bridgeTurnControl('pause'); break;
                case 'turn-stop': bridgeTurnControl('stop'); break;
                case 'toggle-tool-body': toggleToolBody(el); break;
                case 'toggle-subagent': toggleSubAgent(el); break;
                case 'toggle-subagent-step': toggleSubAgentStep(el); break;
                case 'open-file': bridgeOpenFile(el.getAttribute('data-file-path') || ''); break;
                case 'navigate': bridgeNavigate(el.getAttribute('data-ref') || ''); break;
                case 'permission-allow': bridgePermissionDecision(permissionId + ':allow'); break;
                case 'permission-always': bridgePermissionDecision(permissionId + ':always'); break;
                case 'permission-always-scope': bridgePermissionDecision(permissionId + ':always-scope:' + (el.getAttribute('data-scope') || '')); break;
                case 'permission-deny': bridgePermissionDecision(permissionId + ':deny'); break;
                case 'question-submit': {
                    var card = el.closest('.question-card');
                    var answers = card ? collectQuestionAnswers(card) : {};
                    bridgePermissionDecision(permissionId + ':submit:' + JSON.stringify(answers));
                    break;
                }
                case 'question-cancel': bridgePermissionDecision(permissionId + ':cancel'); break;
                case 'drift-action': bridgeDriftAction(el.getAttribute('data-drift-action') || ''); break;
                case 'wiki-git-state-action':
                    console.log('[clawdea] wiki-git-state-action click', el.getAttribute('data-wgs-action'));
                    bridgeWikiGitStateAction(el.getAttribute('data-wgs-action') || '');
                    break;
                case 'run-slash-command': {
                    // Anchor links default to navigating; suppress that so the
                    // page doesn't try to follow href="#".
                    if (e.preventDefault) e.preventDefault();
                    var slash = el.getAttribute('data-slash') || '';
                    if (slash) bridgeRunSlashCommand(slash);
                    break;
                }
            }
        });
    """.trimIndent()

    private fun loadResource(path: String): String =
        ChatHtmlTemplate::class.java.getResourceAsStream(path)!!
            .bufferedReader().readText()
}
