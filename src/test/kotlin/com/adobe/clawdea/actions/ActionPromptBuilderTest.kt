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
package com.adobe.clawdea.actions

import org.junit.Assert.*
import org.junit.Test

class ActionPromptBuilderTest {

    private val builder = ActionPromptBuilder()

    @Test
    fun `explain action includes selected code in user message`() {
        val prompt = builder.build(
            actionType = ActionType.EXPLAIN,
            selectedText = "fun add(a: Int, b: Int) = a + b",
            contextSnippet = "",
            userInstructions = null,
        )

        assertTrue(prompt.userMessage.contains("fun add(a: Int, b: Int) = a + b"))
        assertTrue(prompt.systemPrompt.contains("Explain"))
    }

    @Test
    fun `optimize action requests code changes`() {
        val prompt = builder.build(
            actionType = ActionType.OPTIMIZE,
            selectedText = "for (i in list) { result.add(transform(i)) }",
            contextSnippet = "",
            userInstructions = null,
        )

        assertTrue(prompt.systemPrompt.contains("optimized version"))
        assertTrue(prompt.systemPrompt.contains("Output ONLY the replacement code"))
    }

    @Test
    fun `generate test action mentions test generation`() {
        val prompt = builder.build(
            actionType = ActionType.GENERATE_TEST,
            selectedText = "fun add(a: Int, b: Int) = a + b",
            contextSnippet = "",
            userInstructions = null,
        )

        assertTrue(prompt.systemPrompt.contains("unit test"))
    }

    @Test
    fun `security check action mentions security`() {
        val prompt = builder.build(
            actionType = ActionType.SECURITY_CHECK,
            selectedText = "val query = \"SELECT * FROM users WHERE id = \$id\"",
            contextSnippet = "",
            userInstructions = null,
        )

        assertTrue(prompt.systemPrompt.contains("security"))
    }

    @Test
    fun `add documentation action requests javadoc`() {
        val prompt = builder.build(
            actionType = ActionType.ADD_DOCUMENTATION,
            selectedText = "fun processOrder(order: Order): Receipt",
            contextSnippet = "",
            userInstructions = null,
        )

        assertTrue(prompt.systemPrompt.contains("documentation"))
    }

    @Test
    fun `refactor action includes user instructions`() {
        val prompt = builder.build(
            actionType = ActionType.REFACTOR,
            selectedText = "fun doStuff() { /* long method */ }",
            contextSnippet = "",
            userInstructions = "Extract into two methods: validate and execute",
        )

        assertTrue(prompt.userMessage.contains("Extract into two methods"))
        assertTrue(prompt.systemPrompt.contains("Output ONLY the replacement code"))
    }

    @Test
    fun `ask claude action includes user question`() {
        val prompt = builder.build(
            actionType = ActionType.ASK_CLAUDE,
            selectedText = "val x = mutex.withLock { shared++ }",
            contextSnippet = "",
            userInstructions = "Is this thread-safe?",
        )

        assertTrue(prompt.userMessage.contains("Is this thread-safe?"))
        assertTrue(prompt.userMessage.contains("mutex.withLock"))
    }

    @Test
    fun `context snippet included when not blank`() {
        val prompt = builder.build(
            actionType = ActionType.EXPLAIN,
            selectedText = "doSomething()",
            contextSnippet = "class MyService { fun doSomething() {} }",
            userInstructions = null,
        )

        assertTrue(prompt.systemPrompt.contains("<context>"))
        assertTrue(prompt.systemPrompt.contains("class MyService"))
    }

    @Test
    fun `context snippet omitted when blank`() {
        val prompt = builder.build(
            actionType = ActionType.EXPLAIN,
            selectedText = "doSomething()",
            contextSnippet = "",
            userInstructions = null,
        )

        assertFalse(prompt.systemPrompt.contains("<context>"))
    }

    @Test
    fun `fix action includes diagnostic message`() {
        val prompt = builder.build(
            actionType = ActionType.FIX,
            selectedText = "val x: String = 42",
            contextSnippet = "",
            userInstructions = "Type mismatch: inferred type is Int but String was expected",
        )

        assertTrue(prompt.userMessage.contains("Type mismatch"))
        assertTrue(prompt.systemPrompt.contains("Output ONLY the replacement code"))
    }

    @Test
    fun `code-producing actions have isCodeAction true`() {
        assertTrue(ActionType.OPTIMIZE.isCodeAction)
        assertTrue(ActionType.GENERATE_TEST.isCodeAction)
        assertTrue(ActionType.ADD_DOCUMENTATION.isCodeAction)
        assertTrue(ActionType.REFACTOR.isCodeAction)
        assertTrue(ActionType.FIX.isCodeAction)
    }

    @Test
    fun `text-producing actions have isCodeAction false`() {
        assertFalse(ActionType.EXPLAIN.isCodeAction)
        assertFalse(ActionType.SECURITY_CHECK.isCodeAction)
        assertFalse(ActionType.ASK_CLAUDE.isCodeAction)
    }
}
