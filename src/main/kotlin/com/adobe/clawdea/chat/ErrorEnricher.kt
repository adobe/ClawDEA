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

import com.adobe.clawdea.provider.BackendKind

object ErrorEnricher {
    // Claude/Codex process-backend guidance (unchanged): mentions the Claude API explicitly.
    private val claudePatterns = listOf(
        Regex("authentication|unauthorized|401|invalid.*api.key", RegexOption.IGNORE_CASE) to
            "Authentication failed. Check your API key in Settings > Tools > ClawDEA.",
        Regex("rate.limit|429", RegexOption.IGNORE_CASE) to
            "Rate limited by the API. Wait a moment and try again.",
        Regex("network|connection.refused|connection.*timeout|ECONNREFUSED|ETIMEDOUT", RegexOption.IGNORE_CASE) to
            "Cannot reach the Claude API. Check your network connection and proxy settings.",
        Regex("overloaded|529", RegexOption.IGNORE_CASE) to
            "Claude API is temporarily overloaded. Try again in a few seconds.",
    )

    // OpenAI-compatible provider guidance: provider-neutral wording (never says "Claude API"),
    // and covers the failure modes the HTTP agent backend can surface.
    private val openAiCompatiblePatterns = listOf(
        Regex("authentication|unauthorized|forbidden|access.forbidden|401|403|invalid.*api.key", RegexOption.IGNORE_CASE) to
            "Authentication failed for this provider. Reconnect the profile credential in Settings > Tools > ClawDEA.",
        Regex("rate.limit|too.many.requests|429", RegexOption.IGNORE_CASE) to
            "Rate limited by the provider. Wait for the Retry-After window and try again.",
        Regex("connection.*timeout|read.timeout|timed.out|ETIMEDOUT", RegexOption.IGNORE_CASE) to
            "The provider request timed out. Check the endpoint and your network, then try again.",
        Regex("network|connection.refused|ECONNREFUSED|unknownhost|no route to host", RegexOption.IGNORE_CASE) to
            "Cannot reach the provider endpoint. Check the profile base URL, your network, and proxy settings.",
        Regex("partial.output|interrupted|premature|unexpected end|stream closed|truncated", RegexOption.IGNORE_CASE) to
            "The response was interrupted after partial output. Retry to continue; completed tool calls are reused, not re-run.",
        Regex("tool round limit|too many tool", RegexOption.IGNORE_CASE) to
            "The turn hit the tool-call limit. Simplify the request or split it into smaller steps.",
        Regex("context budget|context.*(limit|exceeded)|maximum context", RegexOption.IGNORE_CASE) to
            "The conversation exceeded this model's context budget. Start a new session or trim the history.",
        Regex("server error|5\\d\\d|overloaded", RegexOption.IGNORE_CASE) to
            "The provider returned a server error. Wait a few seconds and try again.",
    )

    /**
     * Map a raw error string to user-facing guidance, or null if nothing matches.
     *
     * [backendKind] selects the guidance vocabulary: the default preserves the historical
     * Claude/Codex wording, while [BackendKind.OPENAI_COMPATIBLE_HTTP] uses provider-neutral text
     * that never names the Claude API.
     */
    fun enrich(errorText: String, backendKind: BackendKind = BackendKind.CLAUDE_CLI): String? {
        val patterns = when (backendKind) {
            BackendKind.OPENAI_COMPATIBLE_HTTP -> openAiCompatiblePatterns
            else -> claudePatterns
        }
        for ((pattern, guidance) in patterns) {
            if (pattern.containsMatchIn(errorText)) {
                return guidance
            }
        }
        return null
    }
}
