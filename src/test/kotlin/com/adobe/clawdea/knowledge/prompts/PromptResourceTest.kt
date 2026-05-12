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

import org.junit.Assert.*
import org.junit.Test

class PromptResourceTest {

    @Test fun `invariant template loads and is non-empty`() {
        val content = PromptResource.load("wiki-page-invariant")
        assertTrue(content.isNotBlank())
        assertTrue(content.contains("## Invariants"))
        assertTrue(content.contains("## Resolution pipeline"))
        assertTrue(content.contains("## Anti-patterns"))
        assertTrue(content.contains("## Source pointers"))
    }

    @Test fun `navigation template loads and is non-empty`() {
        val content = PromptResource.load("wiki-page-navigation")
        assertTrue(content.isNotBlank())
        assertTrue(content.contains("## Related") || content.contains("## Key entry points"))
    }

    @Test fun `repeated loads hit the cache and return identical content`() {
        val first = PromptResource.load("wiki-page-invariant")
        val second = PromptResource.load("wiki-page-invariant")
        assertSame(first, second)
    }

    @Test fun `missing resource throws IllegalArgumentException`() {
        try {
            PromptResource.load("does-not-exist-xyz")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("does-not-exist-xyz"))
        }
    }
}
