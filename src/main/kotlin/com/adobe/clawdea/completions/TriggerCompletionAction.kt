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
package com.adobe.clawdea.completions

import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware

/**
 * Manually requests a ClawDEA inline completion at the caret, regardless of
 * whether automatic (as-you-type) completions are enabled.
 *
 * This is the configurable hotkey requested in issue #146: pairing it with the
 * "Only request completions on hotkey" setting lets users avoid spending API
 * tokens on incidental typing while still getting a completion on demand. The
 * default keystroke (⌥\ / Alt+\, matching common inline-suggestion tools) is
 * rebindable via Settings → Keymap → "Trigger Inline Completion".
 *
 * A [InlineCompletionEvent.DirectCall] is dispatched through the editor's
 * inline-completion handler; the provider's `isEnabled` treats it as a manual
 * trigger and serves a suggestion even in manual-only mode.
 */
class TriggerCompletionAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        if (!ClawDEASettings.getInstance().state.completionsEnabled) return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val handler = InlineCompletion.getHandlerOrNull(editor) ?: return
        handler.invoke(
            InlineCompletionEvent.DirectCall(editor, editor.caretModel.currentCaret, e.dataContext),
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null &&
            ClawDEASettings.getInstance().state.completionsEnabled
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
