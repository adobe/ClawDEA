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
package com.adobe.clawdea.knowledge.repostate.sections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MavenSectionGeneratorTest {

    private val singleModuleRoot = File("src/test/testData/mapfixtures/single-module")
    private val multiModuleRoot = File("src/test/testData/mapfixtures/multi-module")

    @Test
    fun `parseModules returns submodule paths in order`() {
        val pom = File(multiModuleRoot, "pom.xml").readText()
        val modules = MavenSectionGenerator.parseModules(pom)
        assertEquals(listOf("bundles/acme-modules-api", "bundles/acme-modules-core", "content"), modules)
    }

    @Test
    fun `parseModules returns empty for single-module pom`() {
        val pom = File(singleModuleRoot, "pom.xml").readText()
        assertTrue(MavenSectionGenerator.parseModules(pom).isEmpty())
    }

    @Test
    fun `parseModules handles missing modules block`() {
        assertTrue(MavenSectionGenerator.parseModules("<project></project>").isEmpty())
    }

    @Test
    fun `parseArtifactDescription extracts description`() {
        val pom = File(multiModuleRoot, "bundles/acme-modules-core/pom.xml").readText()
        assertEquals(
            "Modules core implementation. Lives under com.example.app.frontend.modules.impl.",
            MavenSectionGenerator.parseArtifactDescription(pom),
        )
    }

    @Test
    fun `parseArtifactDescription returns null when missing`() {
        assertNull(MavenSectionGenerator.parseArtifactDescription("<project></project>"))
    }

    @Test
    fun `formatBody renders module list with descriptions`() {
        val body = MavenSectionGenerator.formatBody(
            modules = listOf(
                "bundles/acme-modules-api" to "Public API contracts",
                "bundles/acme-modules-core" to "Modules core implementation. Lives under com.example.app.frontend.modules.impl.",
                "content" to null,
            ),
        )
        assertTrue(body.contains("- bundles/acme-modules-api — Public API contracts"))
        assertTrue(body.contains("- bundles/acme-modules-core — Modules core implementation"))
        assertTrue(body.contains("- content"))
    }
}
