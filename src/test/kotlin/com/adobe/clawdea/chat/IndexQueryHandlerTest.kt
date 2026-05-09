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
package com.adobe.clawdea.chat

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.junit.Test

/**
 * Integration tests that require the IntelliJ test sandbox (fixture, PSI indices,
 * ReferencesSearch). These may hang when run headlessly via Gradle — run from the
 * IDE or with appropriate IntelliJ Platform test infrastructure.
 *
 * Pure-logic tests live in IndexQueryHandlerUnitTest.
 */
class IndexQueryHandlerTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    // --- Java callers (full pipeline through fixture) ---

    @Test
    fun testCallersFindsJavaCallSite() {
        myFixture.configureByFiles("CallerSite.java", "CallerTarget.java")
        myFixture.configureByFile("CallerTarget.java")
        val offset = myFixture.file.text.indexOf("targetMethod")

        val handler = IndexQueryHandler(project)
        val html = handler.handleCommand("/callers", myFixture.editor, offset)

        assertTrue("Should find caller in CallerSite", html.contains("CallerSite"))
        assertTrue("Should show 'Callers of targetMethod'", html.contains("Callers of targetMethod"))
    }

    @Test
    fun testCallersReturnsNoResultsForUncalledMethod() {
        myFixture.configureByFile("CallerTarget.java")
        val offset = myFixture.file.text.indexOf("targetMethod")

        val handler = IndexQueryHandler(project)
        val html = handler.handleCommand("/callers", myFixture.editor, offset)

        assertTrue("Should report no results", html.contains("no results found"))
    }

    // --- Offset parameter behavior ---

    @Test
    fun testCallersUsesProvidedOffsetNotCaretPosition() {
        myFixture.configureByFiles("CallerSite.java", "CallerTarget.java")
        myFixture.configureByFile("CallerTarget.java")
        myFixture.editor.caretModel.moveToOffset(0)
        val methodOffset = myFixture.file.text.indexOf("targetMethod")

        val handler = IndexQueryHandler(project)
        val html = handler.handleCommand("/callers", myFixture.editor, methodOffset)

        assertTrue("Should find caller using explicit offset", html.contains("CallerSite"))
    }

    // --- Edge cases that need an editor ---

    @Test
    fun testHandleCommandWithUnknownCommand() {
        myFixture.configureByFile("CallerTarget.java")
        val handler = IndexQueryHandler(project)
        val html = handler.handleCommand("/nonexistent", myFixture.editor)
        assertTrue("Should report unknown command", html.contains("Unknown index command"))
    }

    @Test
    fun testCallersAtNonMethodOffset() {
        myFixture.configureByFile("CallerTarget.java")
        val handler = IndexQueryHandler(project)
        val html = handler.handleCommand("/callers", myFixture.editor, 0)

        // Should either find no method or return the enclosing method
        // The key: it must not crash
        assertNotNull(html)
    }
}
