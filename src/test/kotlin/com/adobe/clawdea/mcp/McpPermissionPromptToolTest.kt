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
package com.adobe.clawdea.mcp

import com.adobe.clawdea.chat.permission.PermissionDispatcher
import com.adobe.clawdea.chat.permission.PermissionPolicy
import com.adobe.clawdea.chat.permission.PermissionRequest
import com.adobe.clawdea.chat.permission.ClaudePermissionRule
import com.adobe.clawdea.chat.permission.ClaudePermissionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class McpPermissionPromptToolTest {

    private fun neverDispatcher(): PermissionDispatcher =
        PermissionDispatcher(onRender = { _ -> /* never resolves */ })

    @Test
    fun `auto-allows clawdea-intellij MCP tools without invoking the dispatcher`() {
        var rendered = false
        val dispatcher = PermissionDispatcher(onRender = { _ -> rendered = true })
        val tool = McpPermissionPromptTool({ dispatcher })
        val result = tool.handle(mapOf(
            "tool_name" to "mcp__clawdea-intellij__find_files",
            "input" to """{"pattern":"foo"}""",
        ))
        assertFalse("dispatcher must not be called for trusted tools", rendered)
        assertFalse(result.isError)
        assertTrue("expected allow in ${result.text}", result.text.contains("\"behavior\":\"allow\""))
    }

    @Test
    fun `auto-allows Read without invoking the dispatcher`() {
        var rendered = false
        val dispatcher = PermissionDispatcher(onRender = { _ -> rendered = true })
        val tool = McpPermissionPromptTool({ dispatcher })
        val result = tool.handle(mapOf("tool_name" to "Read", "input" to """{"file_path":"/tmp/x"}"""))
        assertFalse(rendered)
        assertTrue(result.text.contains("\"behavior\":\"allow\""))
    }

    @Test
    fun `auto-allows Glob and Grep`() {
        val dispatcher = neverDispatcher()
        val tool = McpPermissionPromptTool({ dispatcher })
        val glob = tool.handle(mapOf("tool_name" to "Glob", "input" to """{"pattern":"*.kt"}"""))
        val grep = tool.handle(mapOf("tool_name" to "Grep", "input" to """{"pattern":"foo"}"""))
        assertTrue(glob.text.contains("\"behavior\":\"allow\""))
        assertTrue(grep.text.contains("\"behavior\":\"allow\""))
    }

    @Test
    fun `unknown tool submits to the dispatcher and returns allow on allow`() {
        val renderStarted = CountDownLatch(1)
        lateinit var capturedId: String
        val dispatcher = PermissionDispatcher(onRender = { req ->
            capturedId = req.requestId
            renderStarted.countDown()
        })
        val tool = McpPermissionPromptTool({ dispatcher })

        val resultFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            tool.handle(mapOf("tool_name" to "Bash", "input" to """{"command":"ls"}"""))
        }

        assertTrue(renderStarted.await(2, TimeUnit.SECONDS))
        dispatcher.resolve(capturedId, PermissionRequest.Decision.ALLOW)
        val result = resultFuture.get(2, TimeUnit.SECONDS)
        assertTrue(result.text.contains("\"behavior\":\"allow\""))
    }

    @Test
    fun `unknown tool returns deny on deny`() {
        val renderStarted = CountDownLatch(1)
        lateinit var capturedId: String
        val dispatcher = PermissionDispatcher(onRender = { req ->
            capturedId = req.requestId
            renderStarted.countDown()
        })
        val tool = McpPermissionPromptTool({ dispatcher })

        val resultFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            tool.handle(mapOf("tool_name" to "Bash", "input" to """{"command":"rm -rf /"}"""))
        }

        assertTrue(renderStarted.await(2, TimeUnit.SECONDS))
        dispatcher.resolve(capturedId, PermissionRequest.Decision.DENY)
        val result = resultFuture.get(2, TimeUnit.SECONDS)
        assertTrue("expected deny in ${result.text}", result.text.contains("\"behavior\":\"deny\""))
    }

    @Test
    fun `missing tool_name returns error`() {
        val tool = McpPermissionPromptTool({ neverDispatcher() })
        val result = tool.handle(mapOf("input" to "{}"))
        assertTrue(result.isError)
    }

    @Test
    fun `missing input still auto-allows trusted tools`() {
        val tool = McpPermissionPromptTool({ neverDispatcher() })
        val result = tool.handle(mapOf("tool_name" to "Read"))
        assertFalse(result.isError)
        assertTrue(result.text.contains("\"behavior\":\"allow\""))
    }

    @Test
    fun `allow-all mode silently allows non-trusted tools and notifies the dispatcher`() {
        var submitted = false
        var autoAllowedWith: String? = null
        val dispatcher = PermissionDispatcher(
            onRender = { _ -> submitted = true },
            onAutoAllowed = { req -> autoAllowedWith = req.toolName },
        )
        val tool = McpPermissionPromptTool(
            dispatcherSupplier = { dispatcher },
            toolApprovalModeSupplier = { "allow-all" },
        )
        val result = tool.handle(mapOf("tool_name" to "Bash", "input" to """{"command":"ls"}"""))
        assertFalse("dispatcher.submit must not be called under allow-all", submitted)
        assertEquals("Bash", autoAllowedWith)
        assertTrue(result.text.contains("\"behavior\":\"allow\""))
    }

    @Test
    fun `Claude deny setting wins before allow-all mode and dispatcher prompt`() {
        var submitted = false
        var autoAllowed = false
        val dispatcher = PermissionDispatcher(
            onRender = { _ -> submitted = true },
            onAutoAllowed = { _ -> autoAllowed = true },
        )
        val tool = McpPermissionPromptTool(
            dispatcherSupplier = { dispatcher },
            toolApprovalModeSupplier = { "allow-all" },
            permissionPolicySupplier = {
                PermissionPolicy {
                    ClaudePermissionSettings(
                        deny = listOf(ClaudePermissionRule.parse("Bash(./gradlew publish *)")!!),
                    )
                }
            },
        )

        val result = tool.handle(mapOf("tool_name" to "Bash", "input" to """{"command":"./gradlew publish release"}"""))

        assertFalse("dispatcher.submit must not be called for policy deny", submitted)
        assertFalse("policy deny must not be shown as auto-allowed", autoAllowed)
        assertTrue(result.text.contains("\"behavior\":\"deny\""))
        assertTrue(result.text.contains("Denied by Claude settings"))
    }

    @Test
    fun `Claude allow setting wins before confirm-all dispatcher prompt`() {
        var submitted = false
        val dispatcher = PermissionDispatcher(onRender = { _ -> submitted = true })
        val tool = McpPermissionPromptTool(
            dispatcherSupplier = { dispatcher },
            toolApprovalModeSupplier = { "confirm-all" },
            permissionPolicySupplier = {
                PermissionPolicy {
                    ClaudePermissionSettings(
                        allow = listOf(ClaudePermissionRule.parse("Bash(./gradlew *)")!!),
                    )
                }
            },
        )

        val result = tool.handle(mapOf("tool_name" to "Bash", "input" to """{"command":"./gradlew build"}"""))

        assertFalse("dispatcher.submit must not be called for policy allow", submitted)
        assertTrue(result.text.contains("\"behavior\":\"allow\""))
    }

    @Test
    fun `confirm-all mode does not auto-allow non-trusted tools`() {
        val renderStarted = CountDownLatch(1)
        lateinit var capturedId: String
        val dispatcher = PermissionDispatcher(onRender = { req ->
            capturedId = req.requestId
            renderStarted.countDown()
        })
        val tool = McpPermissionPromptTool(
            dispatcherSupplier = { dispatcher },
            toolApprovalModeSupplier = { "confirm-all" },
        )
        val resultFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            tool.handle(mapOf("tool_name" to "Bash", "input" to """{"command":"ls"}"""))
        }
        assertTrue("submit() must be called under confirm-all", renderStarted.await(2, TimeUnit.SECONDS))
        dispatcher.resolve(capturedId, PermissionRequest.Decision.ALLOW)
        resultFuture.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `allow-safe prompt-tool request prompts for non-trusted tools`() {
        val renderStarted = CountDownLatch(1)
        lateinit var capturedId: String
        val dispatcher = PermissionDispatcher(onRender = { req ->
            capturedId = req.requestId
            renderStarted.countDown()
        })
        val tool = McpPermissionPromptTool(
            dispatcherSupplier = { dispatcher },
            toolApprovalModeSupplier = { "allow-safe" },
        )
        val resultFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            tool.handle(mapOf("tool_name" to "Bash", "input" to """{"command":"mvn test"}"""))
        }
        assertTrue("submit() must be called when the CLI asks under allow-safe", renderStarted.await(2, TimeUnit.SECONDS))
        dispatcher.resolve(capturedId, PermissionRequest.Decision.ALLOW)
        val result = resultFuture.get(2, TimeUnit.SECONDS)
        assertTrue("expected allow in ${result.text}", result.text.contains("\"behavior\":\"allow\""))
        assertTrue("expected original command in ${result.text}", result.text.contains(""""command":"mvn test""""))
    }

    @Test
    fun `deny response tells Claude the command may be retried after later approval`() {
        val result = McpPermissionPromptTool.buildDenyJson("Denied by user")
        assertTrue("expected deny in $result", result.contains("\"behavior\":\"deny\""))
        assertTrue("expected retry guidance in $result", result.contains("try the same tool call again"))
    }

    @Test
    fun `AskUserQuestion is never auto-allowed even under allow-all`() {
        val renderStarted = CountDownLatch(1)
        lateinit var capturedId: String
        var autoAllowedCalled = false
        val dispatcher = PermissionDispatcher(
            onRender = { req ->
                capturedId = req.requestId
                renderStarted.countDown()
            },
            onAutoAllowed = { _ -> autoAllowedCalled = true },
        )
        val tool = McpPermissionPromptTool(
            dispatcherSupplier = { dispatcher },
            toolApprovalModeSupplier = { "allow-all" },
        )
        val resultFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            tool.handle(mapOf(
                "tool_name" to "AskUserQuestion",
                "input" to """{"questions":[{"question":"q","header":"h","options":[{"label":"a","description":""}]}]}""",
            ))
        }
        assertTrue("dispatcher.submit must be invoked for AskUserQuestion", renderStarted.await(2, TimeUnit.SECONDS))
        assertFalse("AskUserQuestion must not flow through onAutoAllowed", autoAllowedCalled)
        dispatcher.resolve(capturedId, PermissionRequest.Decision.ALLOW)
        resultFuture.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `allow path uses updatedInput when the dispatcher provides one`() {
        val renderStarted = CountDownLatch(1)
        lateinit var capturedId: String
        val dispatcher = PermissionDispatcher(onRender = { req ->
            capturedId = req.requestId
            renderStarted.countDown()
        })
        val tool = McpPermissionPromptTool({ dispatcher })
        val resultFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            tool.handle(mapOf(
                "tool_name" to "AskUserQuestion",
                "input" to """{"questions":[]}""",
            ))
        }
        assertTrue(renderStarted.await(2, TimeUnit.SECONDS))
        dispatcher.resolve(
            capturedId,
            PermissionRequest.Decision.ALLOW,
            updatedInput = """{"questions":[],"answers":{"q":"a"}}""",
        )
        val result = resultFuture.get(2, TimeUnit.SECONDS)
        assertTrue("expected merged answers payload, got: ${result.text}",
            result.text.contains(""""answers":{"q":"a"}"""))
    }

    @Test
    fun `allow path falls back to the original input when no updatedInput is provided`() {
        val renderStarted = CountDownLatch(1)
        lateinit var capturedId: String
        val dispatcher = PermissionDispatcher(onRender = { req ->
            capturedId = req.requestId
            renderStarted.countDown()
        })
        val tool = McpPermissionPromptTool({ dispatcher })
        val resultFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            tool.handle(mapOf("tool_name" to "Bash", "input" to """{"command":"ls"}"""))
        }
        assertTrue(renderStarted.await(2, TimeUnit.SECONDS))
        dispatcher.resolve(capturedId, PermissionRequest.Decision.ALLOW)
        val result = resultFuture.get(2, TimeUnit.SECONDS)
        assertTrue("expected pass-through input, got: ${result.text}",
            result.text.contains(""""command":"ls""""))
    }
}
