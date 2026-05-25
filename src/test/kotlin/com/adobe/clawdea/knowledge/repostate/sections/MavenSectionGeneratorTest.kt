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

import com.adobe.clawdea.buildtool.maven.PomReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MavenSectionGeneratorTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val singleModuleRoot = File("src/test/testData/mapfixtures/single-module")
    private val multiModuleRoot = File("src/test/testData/mapfixtures/multi-module")

    @Test
    fun `PomReader readModules returns submodule paths in order`() {
        val modules = PomReader.readModules(File(multiModuleRoot, "pom.xml"))
        assertEquals(listOf("bundles/acme-modules-api", "bundles/acme-modules-core", "content"), modules)
    }

    @Test
    fun `PomReader readModules returns empty for single-module pom`() {
        assertTrue(PomReader.readModules(File(singleModuleRoot, "pom.xml")).isEmpty())
    }

    @Test
    fun `PomReader readDescription extracts description`() {
        assertEquals(
            "Modules core implementation. Lives under com.example.app.frontend.modules.impl.",
            PomReader.readDescription(File(multiModuleRoot, "bundles/acme-modules-core/pom.xml")),
        )
    }

    @Test
    fun `PomReader readDescription returns null when missing`() {
        val pom = tempFolder.newFile("pom.xml")
        pom.writeText("<project><artifactId>x</artifactId></project>")
        assertNull(PomReader.readDescription(pom))
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
