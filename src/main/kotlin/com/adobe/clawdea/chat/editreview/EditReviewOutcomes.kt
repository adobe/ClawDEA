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
package com.adobe.clawdea.chat.editreview

import java.util.concurrent.ConcurrentHashMap

/**
 * Shared store for MCP edit review outcomes.
 * McpEditReviewTools stores the outcome after the diff dialog closes;
 * ChatPanel reads it when the ToolResult event arrives (whose content
 * is empty for MCP tools).
 */
object EditReviewOutcomes {

    private val outcomes = ConcurrentHashMap<String, String>()

    /**
     * Store outcome keyed by file path.
     * Called from MCP handler thread after review completes.
     */
    fun put(filePath: String, outcome: String) {
        outcomes[filePath] = outcome
    }

    /**
     * Retrieve and remove outcome for a file path.
     * Called from ChatPanel when ToolResult arrives with empty content.
     */
    fun take(filePath: String): String? {
        return outcomes.remove(filePath)
    }
}
