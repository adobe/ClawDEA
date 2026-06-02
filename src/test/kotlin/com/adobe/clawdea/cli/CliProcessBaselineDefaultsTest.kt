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
package com.adobe.clawdea.cli

import com.adobe.clawdea.knowledge.prompts.PromptResource
import org.junit.Assert.*
import org.junit.Test

class CliProcessBaselineDefaultsTest {

    @Test
    fun `baseline-defaults resource loads and contains the deference preamble`() {
        val text = PromptResource.load("baseline-defaults")
        assertTrue(text.isNotBlank())
        assertTrue(
            "must name both precedence sources",
            text.contains("your project's CLAUDE.md and any installed workflow skills take precedence"),
        )
    }

    @Test
    fun `baseline-defaults resource contains all four principle keywords`() {
        val text = PromptResource.load("baseline-defaults")
        assertTrue(text.contains("Touch only what the task requires"))
        assertTrue(text.contains("Prefer the simplest change that works"))
        assertTrue(text.contains("Verify before claiming done"))
        assertTrue(text.contains("ask first. Otherwise, proceed"))
    }
}
