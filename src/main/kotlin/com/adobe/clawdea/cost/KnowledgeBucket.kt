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
package com.adobe.clawdea.cost

/** Knowledge-layer upkeep cost buckets. A turn maps to at most one. */
enum class KnowledgeBucket { WIKI_CREATE, WIKI_UPDATE, WORKSPACE_CREATE, WORKSPACE_UPDATE }

/**
 * Classifies a turn's originating prompt into a [KnowledgeBucket], or null for an
 * ordinary turn. Wiki updates cover BOTH the manual `/refresh-wiki` command and the
 * auto-apply `@wiki-author` drift digest (WikiAuthorDigestBuilder prefixes it with
 * "@wiki-author"). Workspace-update is detected from the manifest-update markers.
 */
object KnowledgeBucketClassifier {
    fun classify(promptText: String): KnowledgeBucket? {
        val p = promptText.trimStart()
        return when {
            p.startsWith("/seed-wiki") -> KnowledgeBucket.WIKI_CREATE
            p.startsWith("/refresh-wiki") || p.startsWith("@wiki-author") -> KnowledgeBucket.WIKI_UPDATE
            p.startsWith("/seed-workspace") -> KnowledgeBucket.WORKSPACE_CREATE
            p.startsWith("/refresh-workspace") || p.startsWith("@workspace-author") -> KnowledgeBucket.WORKSPACE_UPDATE
            else -> null
        }
    }

    /**
     * Classify by the originating slash-command name (e.g. "/seed-wiki"). Used at command
     * routing time, where the literal command is known — slash commands dispatch an expanded
     * template to the bridge, not the command text, so [classify] on the sent prompt would miss.
     */
    fun classifyCommand(commandName: String): KnowledgeBucket? = classify(commandName)
}
