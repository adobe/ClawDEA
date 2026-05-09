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
// src/main/kotlin/com/adobe/clawdea/context/ContextAssembler.kt
package com.adobe.clawdea.context

/**
 * Ranks context items by relevance score, trims to a token budget,
 * and formats them as structured text for a Claude prompt.
 */
class ContextAssembler {

    /**
     * Sort items by score descending, then greedily keep items until the token budget is exhausted.
     */
    fun assemble(items: List<ContextItem>, tokenBudget: Int): List<ContextItem> {
        val sorted = items.sortedByDescending { it.score }
        val kept = mutableListOf<ContextItem>()
        var tokensUsed = 0
        for (item in sorted) {
            val itemTokens = item.estimateTokens()
            if (tokensUsed + itemTokens > tokenBudget) continue
            kept.add(item)
            tokensUsed += itemTokens
        }
        return kept
    }

    /**
     * Format kept context items as labeled sections for inclusion in a prompt.
     */
    fun format(items: List<ContextItem>): String {
        if (items.isEmpty()) return ""
        return items.joinToString("\n\n") { item ->
            "--- ${item.label} ---\n${item.content}"
        }
    }
}
