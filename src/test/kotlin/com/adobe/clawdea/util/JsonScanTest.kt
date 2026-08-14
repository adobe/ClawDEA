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
package com.adobe.clawdea.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonScanTest {
    @Test
    fun `string preserves the existing escape semantics`() {
        val json = """{"value":"quote: \" slash: \\ newline: \n tab: \t path: \/tmp"}"""
        assertEquals("quote: \" slash: \\ newline: \n tab: \t path: /tmp", JsonScan.string(json, "value"))
    }

    @Test
    fun `string returns null for missing non-string and blank values`() {
        assertNull(JsonScan.string("""{"other":"x"}""", "value"))
        assertNull(JsonScan.string("""{"value":7}""", "value"))
        assertNull(JsonScan.string("""{"value":""}""", "value"))
    }
}
