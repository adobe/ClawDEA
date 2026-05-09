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

object ErrorEnricher {
    private val patterns = listOf(
        Regex("authentication|unauthorized|401|invalid.*api.key", RegexOption.IGNORE_CASE) to
            "Authentication failed. Check your API key in Settings > Tools > ClawDEA.",
        Regex("rate.limit|429", RegexOption.IGNORE_CASE) to
            "Rate limited by the API. Wait a moment and try again.",
        Regex("network|connection.refused|connection.*timeout|ECONNREFUSED|ETIMEDOUT", RegexOption.IGNORE_CASE) to
            "Cannot reach the Claude API. Check your network connection and proxy settings.",
        Regex("overloaded|529", RegexOption.IGNORE_CASE) to
            "Claude API is temporarily overloaded. Try again in a few seconds.",
    )

    fun enrich(errorText: String): String? {
        for ((pattern, guidance) in patterns) {
            if (pattern.containsMatchIn(errorText)) {
                return guidance
            }
        }
        return null
    }
}
