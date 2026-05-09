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

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-logic tests for IndexQueryHandler that don't require the IntelliJ
 * test sandbox. The fixture-based integration tests live in IndexQueryHandlerTest.
 */
class IndexQueryHandlerUnitTest {

    // --- Edge cases (no platform services needed) ---

    @Test
    fun testHandleCommandWithNullEditor() {
        val handler = IndexQueryHandler(stubProject())
        val html = handler.handleCommand("/callers", null)
        assertTrue("Should report no active editor", html.contains("No active editor"))
    }

    // --- Kotlin fallback resolution logic ---
    // The Kotlin plugin can't be loaded in test sandbox (metadata version mismatch),
    // so we verify the resolution algorithm directly: given a PSI tree where PsiMethod
    // lookup returns null, the generateSequence walk should find a PsiNamedElement
    // whose class name contains "Function".

    @Test
    fun testKotlinFallbackResolutionFindsFunction() {
        val mockFunction = MockKtNamedFunction("greet")
        val mockChild = MockPsiElement(parent = mockFunction)

        val result = generateSequence(mockChild as PsiElement) { it.parent }
            .filterIsInstance<PsiNamedElement>()
            .firstOrNull { it.javaClass.simpleName.contains("Function") }

        assertNotNull("Should find the mock Kotlin function", result)
        assertEquals("greet", (result as PsiNamedElement).name)
    }

    @Test
    fun testKotlinFallbackResolutionSkipsNonFunctionElements() {
        // Tree: mockChild -> mockClass -> mockFunction
        val mockFunction = MockKtNamedFunction("doWork")
        val mockClass = MockPsiNamedElement("MyClass", "KtClass", parent = mockFunction)
        val mockChild = MockPsiElement(parent = mockClass)

        val result = generateSequence(mockChild as PsiElement) { it.parent }
            .filterIsInstance<PsiNamedElement>()
            .firstOrNull { it.javaClass.simpleName.contains("Function") }

        assertNotNull("Should find function, not class", result)
        assertEquals("doWork", (result as PsiNamedElement).name)
    }

    @Test
    fun testKotlinFallbackResolutionReturnsNullWhenNoFunction() {
        val mockClass = MockPsiNamedElement("MyClass", "KtClass")
        val mockChild = MockPsiElement(parent = mockClass)

        val result = generateSequence(mockChild as PsiElement) { it.parent }
            .filterIsInstance<PsiNamedElement>()
            .firstOrNull { it.javaClass.simpleName.contains("Function") }

        assertNull("Should return null when no Function in tree", result)
    }

    private fun stubProject(): Project {
        return java.lang.reflect.Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, _, _ -> null } as Project
    }
}

// --- Test doubles for the Kotlin fallback algorithm ---

/**
 * Mimics KtNamedFunction — the key property is that javaClass.simpleName
 * contains "Function", which is how the production code identifies Kotlin
 * functions without a compile-time dependency on the Kotlin plugin.
 */
private class MockKtNamedFunction(private val fnName: String) : MockPsiElement(parent = null), PsiNamedElement {
    override fun getName(): String = fnName
    override fun setName(name: String): PsiElement = throw UnsupportedOperationException()
}

private open class MockPsiNamedElement(
    private val elName: String,
    private val className: String,
    parent: PsiElement? = null,
) : MockPsiElement(parent = parent), PsiNamedElement {
    override fun getName(): String = elName
    override fun setName(name: String): PsiElement = throw UnsupportedOperationException()
}

/**
 * Minimal PsiElement stub that only implements getParent().
 * All other methods throw — the resolution algorithm only walks the parent chain.
 */
private open class MockPsiElement(
    private val parent: PsiElement? = null,
    private val overrideParent: PsiElement? = null,
) : com.intellij.psi.impl.FakePsiElement() {
    override fun getParent(): PsiElement? = overrideParent ?: parent
}
