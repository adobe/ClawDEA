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
package com.adobe.clawdea.provider.openai.agent

import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextWindowsTest {
    @Test
    fun `returns window for known model`() {
        val profile = OpenAiCompatibleProfile(contextWindows = mapOf("qwen-max" to 32768))
        assertEquals(32768, ContextWindows.forModel(profile, "qwen-max"))
    }

    @Test
    fun `returns null for unknown model`() {
        val profile = OpenAiCompatibleProfile(contextWindows = mapOf("qwen-max" to 32768))
        assertNull(ContextWindows.forModel(profile, "other-model"))
    }

    @Test
    fun `returns null for non-positive window`() {
        val profile = OpenAiCompatibleProfile(contextWindows = mapOf("bad" to 0))
        assertNull(ContextWindows.forModel(profile, "bad"))
    }

    // --- resolve(): catalog column > profile map > conservative default ---

    @Test
    fun `resolve prefers the catalog context window column`() {
        val profile = OpenAiCompatibleProfile(contextWindows = mapOf("qwen" to 32768))
        val catalog = listOf(ModelEntry(id = "qwen", contextWindow = 128_000))
        assertEquals(128_000, ContextWindows.resolve(profile, "qwen", catalog))
    }

    @Test
    fun `resolve falls back to the profile map when catalog value is unset`() {
        val profile = OpenAiCompatibleProfile(contextWindows = mapOf("qwen" to 32768))
        val catalog = listOf(ModelEntry(id = "qwen", contextWindow = 0))
        assertEquals(32768, ContextWindows.resolve(profile, "qwen", catalog))
    }

    @Test
    fun `resolve falls back to the conservative default when nothing is configured`() {
        val profile = OpenAiCompatibleProfile()
        assertEquals(ContextWindows.DEFAULT_CONTEXT_WINDOW_TOKENS, ContextWindows.resolve(profile, "qwen", emptyList()))
        assertEquals(131_072, ContextWindows.DEFAULT_CONTEXT_WINDOW_TOKENS)
    }

    @Test
    fun `resolve ignores a catalog entry for a different model id`() {
        val profile = OpenAiCompatibleProfile()
        val catalog = listOf(ModelEntry(id = "other", contextWindow = 8000))
        assertEquals(ContextWindows.DEFAULT_CONTEXT_WINDOW_TOKENS, ContextWindows.resolve(profile, "qwen", catalog))
    }
}
