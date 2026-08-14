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
package com.adobe.clawdea.provider.openai.agent

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.provider.openai.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LazySubAgentRunnerTest {

    private class FakeRunner : SubAgentRunner {
        override val toolName = "Agent"
        var runs = 0
        override suspend fun run(toolCall: AgentToolCall, emit: (CliEvent) -> Unit): ToolExecutionResult {
            runs++
            return ToolExecutionResult(toolCall.id, "ok", false)
        }
    }

    @Test
    fun `toolName is available without building the delegate`() {
        val builds = AtomicInteger(0)
        val lazy = LazySubAgentRunner(toolName = "Agent") { builds.incrementAndGet(); FakeRunner() }

        assertEquals("Agent", lazy.toolName)
        assertEquals("reading toolName must not build the delegate", 0, builds.get())
    }

    @Test
    fun `the delegate is built on first run and reused thereafter`() = runBlocking {
        val builds = AtomicInteger(0)
        val fake = FakeRunner()
        val lazy = LazySubAgentRunner(toolName = "Agent") { builds.incrementAndGet(); fake }

        lazy.run(AgentToolCall("id1", "Agent", """{"prompt":"a"}"""), emit = {})
        lazy.run(AgentToolCall("id2", "Agent", """{"prompt":"b"}"""), emit = {})

        assertEquals("built exactly once", 1, builds.get())
        assertEquals("both dispatches reached the delegate", 2, fake.runs)
    }
}
