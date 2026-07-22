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
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.provider.AgentSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarianExecutionTest {
    private fun sel(provider: String) = AgentSelection(providerId = provider, profileId = null, modelId = "m")

    @Test fun anthropic_runs_claude_subprocess() =
        assertEquals(LibrarianExecution.CLAUDE_SUBPROCESS, chooseLibrarianExecution(sel("anthropic")))

    @Test fun bedrock_runs_claude_subprocess() =
        assertEquals(LibrarianExecution.CLAUDE_SUBPROCESS, chooseLibrarianExecution(sel("bedrock")))

    @Test fun subscription_runs_claude_subprocess() =
        assertEquals(LibrarianExecution.CLAUDE_SUBPROCESS, chooseLibrarianExecution(sel("subscription")))

    @Test fun openai_compatible_runs_agentic_loop() =
        assertEquals(LibrarianExecution.AGENTIC_LOOP, chooseLibrarianExecution(sel("openai-compatible")))

    @Test fun codex_runs_codex_subprocess() =
        assertEquals(LibrarianExecution.CODEX_SUBPROCESS, chooseLibrarianExecution(sel("openai")))

    @Test fun unknown_provider_defaults_to_claude_subprocess() =
        assertEquals(LibrarianExecution.CLAUDE_SUBPROCESS, chooseLibrarianExecution(sel("totally-unknown")))
}
