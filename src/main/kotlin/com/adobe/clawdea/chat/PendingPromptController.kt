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

/**
 * Owns only the pending prompt state; the Swing composer owns the editable text.
 * [consume] receives the latest composer text so user edits before flush win.
 */
class PendingPromptController {
    var isQueued: Boolean = false
        private set

    fun queue(composerText: String): Boolean {
        if (composerText.isBlank()) {
            clear()
            return false
        }
        isQueued = true
        return true
    }

    fun consume(composerText: String): String? {
        if (!isQueued) return null
        isQueued = false
        return composerText.trim().ifBlank { null }
    }

    fun clear() {
        isQueued = false
    }

    fun statusText(composerText: String): String {
        return if (isQueued && composerText.isNotBlank()) {
            "Queued next prompt - will send after this turn"
        } else {
            "Claude is thinking..."
        }
    }
}
