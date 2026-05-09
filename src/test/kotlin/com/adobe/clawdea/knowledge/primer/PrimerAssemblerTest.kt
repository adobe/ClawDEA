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
package com.adobe.clawdea.knowledge.primer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimerAssemblerTest {

    @Test
    fun `assemble wraps each source in a labeled fence`() {
        val parts = linkedMapOf(
            "claudeMd" to "# Project rules\n\nUse Kotlin only.",
            "map" to "# Project map\n\n## Modules\n- foo",
        )
        val out = PrimerAssembler.assemble(parts)
        assertTrue(out.contains("<primer source=\"claudeMd\">"))
        assertTrue(out.contains("# Project rules"))
        assertTrue(out.contains("</primer>"))
        assertTrue(out.contains("<primer source=\"map\">"))
        assertTrue(out.indexOf("source=\"claudeMd\"") < out.indexOf("source=\"map\""))
    }

    @Test
    fun `assemble omits empty parts`() {
        val parts = linkedMapOf(
            "claudeMd" to "# Project rules",
            "map" to "",
            "current" to null,
        )
        val out = PrimerAssembler.assemble(parts)
        assertTrue(out.contains("source=\"claudeMd\""))
        assertTrue(!out.contains("source=\"map\""))
        assertTrue(!out.contains("source=\"current\""))
    }

    @Test
    fun `assemble returns empty string when nothing to include`() {
        assertEquals("", PrimerAssembler.assemble(linkedMapOf()))
        assertEquals("", PrimerAssembler.assemble(linkedMapOf("a" to null, "b" to "")))
    }

    @Test
    fun `assemble produces a stable byte-identical output for identical input`() {
        val parts = linkedMapOf("claudeMd" to "X", "map" to "Y")
        assertEquals(PrimerAssembler.assemble(parts), PrimerAssembler.assemble(parts))
    }
}
