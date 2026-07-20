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
package com.adobe.clawdea.provider.openai.tools

import com.adobe.clawdea.chat.permission.ClaudePermissionSettings
import com.adobe.clawdea.chat.permission.PermissionDispatcher
import com.adobe.clawdea.chat.permission.PermissionPolicy
import com.adobe.clawdea.chat.permission.PermissionRequest
import com.adobe.clawdea.chat.permission.PermissionRouterRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedToolApprovalGateTest {

    @Test
    fun `autoAcceptEdit short-circuits to allow`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "confirm-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val result = gate.approve("apply_patch", "{}", "tool-1", autoAcceptEdit = true, MissingRouteBehavior.DENY)
        assertEquals(true, result)
    }

    @Test
    fun `allow-all mode approves`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "allow-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val result = gate.approve("Bash", """{"command":"ls"}""", "tool-1", autoAcceptEdit = false, MissingRouteBehavior.DENY)
        assertEquals(true, result)
    }

    @Test
    fun `policy DENY denies`() {
        val denySettings = ClaudePermissionSettings(
            deny = listOf(
                com.adobe.clawdea.chat.permission.ClaudePermissionRule("Bash", "Bash", null),
            ),
        )
        val denyPolicy = PermissionPolicy { denySettings }
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "confirm-all" },
            policy = { denyPolicy },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val result = gate.approve("Bash", """{"command":"rm"}""", "tool-1", autoAcceptEdit = false, MissingRouteBehavior.DENY)
        assertEquals(false, result)
    }

    @Test
    fun `policy ALLOW allows`() {
        val allowSettings = ClaudePermissionSettings(
            allow = listOf(
                com.adobe.clawdea.chat.permission.ClaudePermissionRule("Bash", "Bash", null),
            ),
        )
        val allowPolicy = PermissionPolicy { allowSettings }
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "confirm-all" },
            policy = { allowPolicy },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val result = gate.approve("Bash", """{"command":"ls"}""", "tool-1", autoAcceptEdit = false, MissingRouteBehavior.DENY)
        assertEquals(true, result)
    }

    @Test
    fun `missing route with DENY behavior denies`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "confirm-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val result = gate.approve("Bash", """{"command":"ls"}""", "tool-1", autoAcceptEdit = false, MissingRouteBehavior.DENY)
        assertEquals(false, result)
    }

    @Test
    fun `missing route with APPROVE_FOR_CODEX_COMPATIBILITY allows`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "confirm-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val result = gate.approve("Bash", """{"command":"ls"}""", "tool-1", autoAcceptEdit = false, MissingRouteBehavior.APPROVE_FOR_CODEX_COMPATIBILITY)
        assertEquals(true, result)
    }

    @Test
    fun `timeout denies`() {
        val fakeDispatcher = object : PermissionDispatcher({}) {
            override fun submit(toolName: String, inputJson: String, timeoutMs: Long, toolUseId: String?): Result {
                return Result(PermissionRequest.Decision.ALLOW, timedOut = true)
            }
        }
        val routed = PermissionRouterRegistry.Routed(toolUseId = "tool-1", dispatcher = fakeDispatcher)
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "confirm-all" },
            policy = { null },
            route = { _, _, _ -> routed },
            promptTimeoutMs = 1000,
        )
        val result = gate.approve("Bash", """{"command":"ls"}""", "tool-1", autoAcceptEdit = false, MissingRouteBehavior.DENY)
        assertEquals(false, result)
    }

    @Test
    fun `dispatcher ALLOW allows`() {
        val fakeDispatcher = object : PermissionDispatcher({}) {
            override fun submit(toolName: String, inputJson: String, timeoutMs: Long, toolUseId: String?): Result {
                return Result(PermissionRequest.Decision.ALLOW, timedOut = false)
            }
        }
        val routed = PermissionRouterRegistry.Routed(toolUseId = "tool-1", dispatcher = fakeDispatcher)
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "confirm-all" },
            policy = { null },
            route = { _, _, _ -> routed },
            promptTimeoutMs = 1000,
        )
        val result = gate.approve("Bash", """{"command":"ls"}""", "tool-1", autoAcceptEdit = false, MissingRouteBehavior.DENY)
        assertEquals(true, result)
    }

    @Test
    fun `dispatcher DENY denies`() {
        val fakeDispatcher = object : PermissionDispatcher({}) {
            override fun submit(toolName: String, inputJson: String, timeoutMs: Long, toolUseId: String?): Result {
                return Result(PermissionRequest.Decision.DENY, timedOut = false)
            }
        }
        val routed = PermissionRouterRegistry.Routed(toolUseId = "tool-1", dispatcher = fakeDispatcher)
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "confirm-all" },
            policy = { null },
            route = { _, _, _ -> routed },
            promptTimeoutMs = 1000,
        )
        val result = gate.approve("Bash", """{"command":"ls"}""", "tool-1", autoAcceptEdit = false, MissingRouteBehavior.DENY)
        assertEquals(false, result)
    }
}
