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
// src/test/kotlin/com/adobe/clawdea/completions/CompletionPromptBuilderTest.kt
package com.adobe.clawdea.completions

import org.junit.Assert.*
import org.junit.Test

class CompletionPromptBuilderTest {

    private val builder = CompletionPromptBuilder()

    @Test
    fun `build places prefix before CURSOR marker in user message`() {
        val doc = "public class Foo {\n    public void bar() {\n        \n    }\n}"
        val offset = doc.indexOf("        \n")

        val prompt = builder.build(doc, offset, "")

        assertTrue("User message should contain code before cursor",
            prompt.userMessage.contains("public class Foo"))
        assertTrue("User message should end with CURSOR marker",
            prompt.userMessage.contains("<CURSOR>"))
    }

    @Test
    fun `build includes suffix in system prompt`() {
        val doc = "public class Foo {\n    public void bar() {\n        \n    }\n}"
        val offset = doc.indexOf("        \n")

        val prompt = builder.build(doc, offset, "")

        assertTrue("System prompt should contain code_after_cursor section",
            prompt.systemPrompt.contains("<code_after_cursor>"))
        assertTrue("System prompt should contain code after cursor",
            prompt.systemPrompt.contains("}"))
    }

    @Test
    fun `build includes context snippet when provided`() {
        val doc = "class Foo {  }"
        val context = "class Bar { void baz() {} }"

        val prompt = builder.build(doc, 12, context)

        assertTrue("System prompt should contain context section",
            prompt.systemPrompt.contains("<context>"))
        assertTrue("System prompt should contain context content",
            prompt.systemPrompt.contains("class Bar"))
    }

    @Test
    fun `build omits context section when context is blank`() {
        val doc = "class Foo {  }"

        val prompt = builder.build(doc, 12, "")

        assertFalse("System prompt should not contain context section",
            prompt.systemPrompt.contains("<context>"))
    }

    @Test
    fun `build omits suffix section when cursor is at end of document`() {
        val doc = "class Foo {"

        val prompt = builder.build(doc, doc.length, "")

        assertFalse("System prompt should not contain code_after_cursor when at end",
            prompt.systemPrompt.contains("<code_after_cursor>"))
    }

    @Test
    fun `build handles cursor at start of document`() {
        val doc = "public class Foo {}"

        val prompt = builder.build(doc, 0, "")

        assertTrue("User message should contain CURSOR marker",
            prompt.userMessage.contains("<CURSOR>"))
        assertTrue("System prompt should contain full file as suffix",
            prompt.systemPrompt.contains("public class Foo {}"))
    }

    @Test
    fun `build truncates prefix to last 30 lines`() {
        val lines = (1..60).map { "val x$it = $it" }
        val doc = lines.joinToString("\n")

        val prompt = builder.build(doc, doc.length, "")

        assertFalse("Should not contain early lines",
            prompt.userMessage.contains("val x1 ="))
        assertTrue("Should contain recent lines",
            prompt.userMessage.contains("val x60"))
    }

    @Test
    fun `build truncates suffix to first 10 lines`() {
        val prefix = "val prefix = 1\n"
        val suffixLines = (1..30).map { "val suffix$it = $it" }
        val doc = prefix + suffixLines.joinToString("\n")

        val prompt = builder.build(doc, prefix.length, "")

        assertTrue("Should contain early suffix lines",
            prompt.systemPrompt.contains("val suffix1"))
        assertFalse("Should not contain late suffix lines",
            prompt.systemPrompt.contains("val suffix15"))
    }

    @Test
    fun `build system prompt includes completion instructions`() {
        val doc = "class Foo {}"

        val prompt = builder.build(doc, 10, "")

        assertTrue("Should instruct to output only code",
            prompt.systemPrompt.contains("Output ONLY the code"))
        assertTrue("Should instruct no explanation",
            prompt.systemPrompt.contains("Do not include any explanation"))
    }

    @Test
    fun `build adds comment hint for line comment`() {
        val doc = "fun foo() {\n    // "
        val prompt = builder.build(doc, doc.length, "")

        assertTrue("Should hint that cursor is in a comment",
            prompt.systemPrompt.contains("inside a line comment"))
        assertTrue("Should instruct not to generate code",
            prompt.systemPrompt.contains("Do NOT generate code"))
    }

    @Test
    fun `build adds comment hint for block comment`() {
        val doc = "fun foo() {\n    /* "
        val prompt = builder.build(doc, doc.length, "")

        assertTrue("Should hint that cursor is in a block comment",
            prompt.systemPrompt.contains("inside a block comment"))
    }

    @Test
    fun `build adds comment hint for block comment continuation`() {
        val doc = "/**\n * "
        val prompt = builder.build(doc, doc.length, "")

        assertTrue("Should hint that cursor is in a block comment",
            prompt.systemPrompt.contains("inside a block comment"))
    }

    @Test
    fun `build adds comment hint for hash comment`() {
        val doc = "# "
        val prompt = builder.build(doc, doc.length, "")

        assertTrue("Should hint that cursor is in a comment",
            prompt.systemPrompt.contains("inside a comment"))
    }

    @Test
    fun `build does not add comment hint for normal code line`() {
        val doc = "fun foo() {\n    val x = "
        val prompt = builder.build(doc, doc.length, "")

        assertFalse("Should not contain comment hint for code lines",
            prompt.systemPrompt.contains("inside a"))
    }

    @Test
    fun `needsSemanticContext returns false for comment lines`() {
        assertFalse(CompletionPromptBuilder.needsSemanticContext("fun foo() {\n    // ", 19))
    }

    @Test
    fun `needsSemanticContext returns false for blank lines`() {
        assertFalse(CompletionPromptBuilder.needsSemanticContext("fun foo() {\n    ", 17))
    }

    @Test
    fun `needsSemanticContext returns true for code lines`() {
        val doc = "fun foo() {\n    val x = "
        assertTrue(CompletionPromptBuilder.needsSemanticContext(doc, doc.length))
    }
}
