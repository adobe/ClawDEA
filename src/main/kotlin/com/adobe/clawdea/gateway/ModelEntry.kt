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
package com.adobe.clawdea.gateway

/**
 * One row in the model catalog shown in the chat model selector and in Settings.
 * Mutable vars + default values satisfy PersistentStateComponent's XmlSerializer.
 */
data class ModelEntry(
    var id: String = "",
    var displayName: String = "",
    var userAdded: Boolean = false,
)

val DEFAULT_MODEL_CATALOG: List<ModelEntry> = listOf(
    ModelEntry(id = "claude-opus-4-7",   displayName = "Claude Opus 4.7"),
    ModelEntry(id = "claude-sonnet-4-6", displayName = "Claude Sonnet 4.6"),
    ModelEntry(id = "claude-haiku-4-5",  displayName = "Claude Haiku 4.5"),
)

val DEFAULT_ANTHROPIC_CATALOG: List<ModelEntry> = DEFAULT_MODEL_CATALOG

val DEFAULT_BEDROCK_CATALOG: List<ModelEntry> = listOf(
    ModelEntry(id = "us.anthropic.claude-opus-4-7",                    displayName = "Claude Opus 4.7"),
    ModelEntry(id = "us.anthropic.claude-opus-4-6-v1",                 displayName = "Claude Opus 4.6"),
    ModelEntry(id = "us.anthropic.claude-opus-4-5-20251101-v1:0",      displayName = "Claude Opus 4.5"),
    ModelEntry(id = "us.anthropic.claude-opus-4-1-20250805-v1:0",      displayName = "Claude Opus 4.1"),
    ModelEntry(id = "us.anthropic.claude-sonnet-4-6",                  displayName = "Claude Sonnet 4.6"),
    ModelEntry(id = "us.anthropic.claude-sonnet-4-5-20250929-v1:0",    displayName = "Claude Sonnet 4.5"),
    ModelEntry(id = "us.anthropic.claude-haiku-4-5-20251001-v1:0",     displayName = "Claude Haiku 4.5"),
)

/**
 * Subscription shares model IDs with the direct Anthropic API. This is only the
 * initial seed shown before [SubscriptionModelProbe] replaces it with the live
 * list fetched via the OAuth bearer (from `~/.claude/.credentials.json` on Linux,
 * or the macOS Keychain under service `Claude Code-credentials`).
 */
val DEFAULT_SUBSCRIPTION_CATALOG: List<ModelEntry> = DEFAULT_ANTHROPIC_CATALOG

fun defaultModelCatalogsMap(): MutableMap<String, MutableList<ModelEntry>> = mutableMapOf(
    "anthropic"    to DEFAULT_ANTHROPIC_CATALOG.toMutableList(),
    "bedrock"      to DEFAULT_BEDROCK_CATALOG.toMutableList(),
    "vertex"       to mutableListOf(),
    "subscription" to DEFAULT_SUBSCRIPTION_CATALOG.toMutableList(),
)
