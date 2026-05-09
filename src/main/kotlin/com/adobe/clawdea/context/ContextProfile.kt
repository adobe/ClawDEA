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
// src/main/kotlin/com/adobe/clawdea/context/ContextProfile.kt
package com.adobe.clawdea.context

import com.adobe.clawdea.settings.ClawDEASettings

/**
 * Defines which collectors to use and how much token budget each feature gets.
 */
enum class ContextProfile(
    val usePsi: Boolean,
    val useFiles: Boolean,
    val useGit: Boolean,
    val useIndex: Boolean,
) {
    /** Tight, focused context for inline completions. */
    COMPLETION(usePsi = true, useFiles = false, useGit = false, useIndex = true),

    /** Broad context for chat / agentic tasks. */
    CHAT(usePsi = true, useFiles = true, useGit = true, useIndex = true),

    /** Focused context for editor actions (explain, optimize, etc.). */
    ACTION(usePsi = true, useFiles = true, useGit = false, useIndex = true);

    fun getTokenBudget(): Int {
        val settings = ClawDEASettings.getInstance().state
        return when (this) {
            COMPLETION -> settings.completionTokenBudget
            CHAT -> settings.chatTokenBudget
            ACTION -> settings.actionTokenBudget
        }
    }
}
