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

class ActionExecutorTest {

    @Test
    fun `buildGatewayRequest produces correct request`() {
        val prompt = ActionPromptBuilder.ActionPrompt(
            systemPrompt = "You are a code optimizer.",
            userMessage = "```\nval x = 1\n```",
        )

        val request = ActionExecutor.buildGatewayRequest(prompt)

        assertEquals("claude-sonnet-4-6", request.model)
        assertEquals(1024, request.maxTokens)
        assertEquals("You are a code optimizer.", request.systemPrompt)
        assertEquals("```\nval x = 1\n```", request.userMessage)
        assertEquals(30L, request.timeoutSeconds)
    }

    @Test
    fun `extractCodeFromResponse strips code fences if present`() {
        val response = "```kotlin\nfun add(a: Int, b: Int) = a + b\n```"

        val result = ActionExecutor.extractCodeFromResponse(response)

        assertEquals("fun add(a: Int, b: Int) = a + b", result)
    }

    @Test
    fun `extractCodeFromResponse returns raw text when no fences`() {
        val response = "fun add(a: Int, b: Int) = a + b"

        val result = ActionExecutor.extractCodeFromResponse(response)

        assertEquals("fun add(a: Int, b: Int) = a + b", result)
    }

    @Test
    fun `extractCodeFromResponse handles fences without language tag`() {
        val response = "```\nfun add(a: Int, b: Int) = a + b\n```"

        val result = ActionExecutor.extractCodeFromResponse(response)

        assertEquals("fun add(a: Int, b: Int) = a + b", result)
    }

    @Test
    fun `extractCodeFromResponse handles multiple code blocks by taking first`() {
        val response = "```kotlin\nfun a() = 1\n```\n\nSome explanation\n\n```kotlin\nfun b() = 2\n```"

        val result = ActionExecutor.extractCodeFromResponse(response)

        assertEquals("fun a() = 1", result)
    }

    @Test
    fun `extractCodeFromResponse trims whitespace`() {
        val response = "  \n  fun add(a: Int, b: Int) = a + b  \n  "

        val result = ActionExecutor.extractCodeFromResponse(response)

        assertEquals("fun add(a: Int, b: Int) = a + b", result)
    }

    @Test
    fun `buildGatewayRequest uses selected model when provided`() {
        val prompt = ActionPromptBuilder.ActionPrompt(
            systemPrompt = "You are a code optimizer.",
            userMessage = "```\nval x = 1\n```",
        )

        val request = ActionExecutor.buildGatewayRequest(prompt, modelId = "claude-opus-4-7")

        assertEquals("claude-opus-4-7", request.model)
        assertEquals(1024, request.maxTokens)
        assertEquals("You are a code optimizer.", request.systemPrompt)
        assertEquals("```\nval x = 1\n```", request.userMessage)
        assertEquals(30L, request.timeoutSeconds)
    }

    @Test
    fun `buildGatewayRequest falls back to sonnet when model is blank`() {
        val prompt = ActionPromptBuilder.ActionPrompt(
            systemPrompt = "You are a code optimizer.",
            userMessage = "```\nval x = 1\n```",
        )

        val request = ActionExecutor.buildGatewayRequest(prompt, modelId = "")

        assertEquals("claude-sonnet-4-6", request.model)
    }
}
