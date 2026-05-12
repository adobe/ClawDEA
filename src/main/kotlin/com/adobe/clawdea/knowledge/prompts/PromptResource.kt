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
package com.adobe.clawdea.knowledge.prompts

import java.util.concurrent.ConcurrentHashMap

/**
 * Loads prompt templates from `src/main/resources/prompts/<name>.md` and caches
 * their contents in-memory. Lets `/learn` and `/seed-wiki` iterate on templates
 * without a Kotlin rebuild (resources are re-read each process start).
 */
object PromptResource {

    private val cache = ConcurrentHashMap<String, String>()

    fun load(name: String): String = cache.getOrPut(name) {
        val resourcePath = "/prompts/$name.md"
        val stream = PromptResource::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Prompt resource not found: $resourcePath")
        stream.use { it.bufferedReader(Charsets.UTF_8).readText() }
    }
}
