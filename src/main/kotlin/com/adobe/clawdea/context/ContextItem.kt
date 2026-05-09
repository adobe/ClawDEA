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
// src/main/kotlin/com/adobe/clawdea/context/ContextItem.kt
package com.adobe.clawdea.context

/**
 * A single piece of context with a relevance score.
 * Higher score = more relevant = kept when trimming.
 */
data class ContextItem(
    /** Human-readable label, e.g. "Current method: doGet" or "Open file: LoginServlet.java" */
    val label: String,
    /** The actual context text to include in the prompt */
    val content: String,
    /** Relevance score 0.0-1.0. Higher = more likely to be kept. */
    val score: Double,
    /** Source collector for debugging */
    val source: String,
) {
    /** Rough token estimate: ~4 chars per token */
    fun estimateTokens(): Int = (content.length + label.length) / 4
}
