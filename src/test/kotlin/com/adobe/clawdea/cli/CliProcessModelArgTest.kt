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

import org.junit.Assert.assertEquals
import org.junit.Test

class CliProcessModelArgTest {

    @Test
    fun `buildModelArg returns empty list for empty string`() {
        assertEquals(emptyList<String>(), CliProcess.buildModelArg(""))
    }

    @Test
    fun `buildModelArg returns empty list for blank string`() {
        assertEquals(emptyList<String>(), CliProcess.buildModelArg("   "))
    }

    @Test
    fun `buildModelArg returns --model and id for a non-empty string`() {
        assertEquals(listOf("--model", "claude-opus-4-7"), CliProcess.buildModelArg("claude-opus-4-7"))
    }
}
