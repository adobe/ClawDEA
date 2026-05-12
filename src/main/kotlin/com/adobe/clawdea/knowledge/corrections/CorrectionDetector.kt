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
package com.adobe.clawdea.knowledge.corrections

data class CorrectionSignal(
    val userMessage: String,
    val priorAssistantMessage: String,
    val suggestedTopic: String,
)

object CorrectionDetector {

    private val CORRECTION_RX = Regex(
        "\\b(no|wrong|actually|missed|that's not|that is not|incorrect|hmm|not quite)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val STOPWORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "no", "not", "nor", "so", "and", "or", "but", "if", "then", "else",
        "to", "of", "in", "on", "at", "by", "for", "with", "from", "as",
        "that", "this", "these", "those", "it", "its", "they", "them", "their",
        "i", "you", "he", "she", "we", "us", "our", "your",
        "do", "does", "did", "have", "has", "had",
        "would", "could", "should", "will", "shall", "may", "might", "must", "can",
        "actually", "wrong", "missed", "incorrect", "hmm", "quite", "really",
        "just", "very", "also", "too", "about", "because", "why",
    )

    fun detect(userMsg: String, priorAssistantMsg: String): CorrectionSignal? {
        if (priorAssistantMsg.isBlank()) return null
        val trimmed = userMsg.trim()
        if (trimmed.length < 4) return null
        if (!CORRECTION_RX.containsMatchIn(trimmed)) return null
        return CorrectionSignal(
            userMessage = trimmed,
            priorAssistantMessage = priorAssistantMsg,
            suggestedTopic = draftTopic(trimmed),
        )
    }

    fun draftTopic(correctionText: String): String {
        val tokens = correctionText
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in STOPWORDS && it.length > 1 }
        return tokens.take(5).joinToString("-").ifBlank { "correction" }
    }
}
