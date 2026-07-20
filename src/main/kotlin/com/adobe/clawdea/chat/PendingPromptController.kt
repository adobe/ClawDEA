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

data class PendingInputTransfer(
    val queuedPrompt: String?,
    val composerText: String?,
) {
    fun visibleComposerText(): String =
        listOfNotNull(queuedPrompt, composerText).joinToString("\n\n")
}

/**
 * Owns only the pending prompt state; the Swing composer owns the editable text.
 * [consume] receives the latest composer text so user edits before flush win.
 */
class PendingPromptController {
    var isQueued: Boolean = false
        private set
    private var explicitPrompt: String? = null

    val hasExplicitPrompt: Boolean
        get() = explicitPrompt != null

    fun queue(composerText: String): Boolean {
        if (composerText.isBlank()) {
            clear()
            return false
        }
        explicitPrompt = null
        isQueued = true
        return true
    }

    fun queueExplicit(prompt: String): Boolean {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return false
        explicitPrompt = trimmed
        isQueued = true
        return true
    }

    fun consume(composerText: String): String? {
        if (!isQueued) return null
        val queuedPrompt = explicitPrompt
        isQueued = false
        explicitPrompt = null
        if (queuedPrompt != null) return queuedPrompt
        return composerText.trim().ifBlank { null }
    }

    /**
     * Removes input from the old panel before a backend rebuild and returns a draft for the
     * replacement panel. Once captured, [consume] cannot dispatch it through the stale bridge.
     */
    fun captureForBackendRebuild(composerText: String): PendingInputTransfer {
        val queuedPrompt = if (isQueued) {
            explicitPrompt ?: composerText.trim().ifBlank { null }
        } else {
            null
        }
        val composerDraft = composerText.takeIf { text ->
            text.isNotBlank() && text.trim() != queuedPrompt
        }
        clear()
        return PendingInputTransfer(queuedPrompt, composerDraft)
    }

    fun clear() {
        isQueued = false
        explicitPrompt = null
    }

    fun statusText(composerText: String): String {
        return if (isQueued && (composerText.isNotBlank() || explicitPrompt != null)) {
            "Queued next prompt - will send after this turn"
        } else {
            "Claude is thinking..."
        }
    }
}
