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
package com.adobe.clawdea.provider.openai.client

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimModelDisplayNameTest {

    @Test
    fun `slash-delimited id trims to last segment`() {
        assertEquals("Qwen3.6-35B-A3B", trimModelDisplayName("hosted_vllm/Qwen/Qwen3.6-35B-A3B"))
    }

    @Test
    fun `no-slash id is unchanged`() {
        assertEquals("text-embedding-ada-002", trimModelDisplayName("text-embedding-ada-002"))
    }

    @Test
    fun `simple three-segment path trims to last`() {
        assertEquals("c", trimModelDisplayName("a/b/c"))
    }
}
