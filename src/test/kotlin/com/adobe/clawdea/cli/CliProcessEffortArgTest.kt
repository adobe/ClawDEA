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

class CliProcessEffortArgTest {

    @Test
    fun `buildEffortArg returns empty list for empty string`() {
        assertEquals(emptyList<String>(), CliProcess.buildEffortArg(""))
    }

    @Test
    fun `buildEffortArg returns empty list for blank string`() {
        assertEquals(emptyList<String>(), CliProcess.buildEffortArg("   "))
    }

    @Test
    fun `buildEffortArg returns --effort and level for a non-empty string`() {
        assertEquals(listOf("--effort", "high"), CliProcess.buildEffortArg("high"))
    }

    @Test
    fun `buildEffortArg trims surrounding whitespace`() {
        assertEquals(listOf("--effort", "max"), CliProcess.buildEffortArg("  max  "))
    }
}
