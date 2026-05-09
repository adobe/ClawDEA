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
package com.adobe.clawdea.cli

import com.adobe.clawdea.auth.AuthValidation
import org.junit.Assert.*
import org.junit.Test

class CliProcessPreflightTest {

    @Test
    fun `preflight throws CliStartException when binary does not exist`() {
        val exception = assertThrows(CliStartException::class.java) {
            CliProcess.preflightChecks(
                cliPath = "/nonexistent/path/to/claude",
                processEnv = emptyMap(),
                authValidation = AuthValidation(valid = true, message = null),
            )
        }
        assertTrue(exception.message.contains("Claude CLI not found"))
        assertTrue(exception.message.contains("npm install"))
    }

    @Test
    fun `preflight throws CliStartException when binary is not executable`() {
        val tmpFile = java.io.File.createTempFile("claude-test", "")
        tmpFile.deleteOnExit()
        tmpFile.setExecutable(false)

        val exception = assertThrows(CliStartException::class.java) {
            CliProcess.preflightChecks(
                cliPath = tmpFile.absolutePath,
                processEnv = emptyMap(),
                authValidation = AuthValidation(valid = true, message = null),
            )
        }
        assertTrue(exception.message.contains("Claude CLI not found"))
    }

    @Test
    fun `preflight throws CliStartException when auth validation fails`() {
        // Use a real executable so binary check passes — /bin/echo is always present
        val exception = assertThrows(CliStartException::class.java) {
            CliProcess.preflightChecks(
                cliPath = "/bin/echo",
                processEnv = emptyMap(),
                authValidation = AuthValidation(valid = false, message = "No auth"),
            )
        }
        assertTrue(exception.message.contains("No auth"))
    }

    @Test
    fun `preflight passes when auth validation succeeds`() {
        // Should NOT throw — auth is valid
        CliProcess.preflightChecks(
            cliPath = "/bin/echo",
            processEnv = emptyMap(),
            authValidation = AuthValidation(valid = true, message = null),
        )
    }

    @Test
    fun `preflight passes when all checks succeed`() {
        CliProcess.preflightChecks(
            cliPath = "/bin/echo",
            processEnv = emptyMap(),
            authValidation = AuthValidation(valid = true, message = null),
        )
    }

    @Test
    fun `preflight uses default message when auth validation message is null`() {
        val exception = assertThrows(CliStartException::class.java) {
            CliProcess.preflightChecks(
                cliPath = "/bin/echo",
                processEnv = emptyMap(),
                authValidation = AuthValidation(valid = false, message = null),
            )
        }
        assertTrue(exception.message.contains("No authentication configured"))
    }

    @Test
    fun `preflight passes when auth fails but processEnv has CLAUDE_CODE_USE_BEDROCK`() {
        CliProcess.preflightChecks(
            cliPath = "/bin/echo",
            processEnv = mapOf("CLAUDE_CODE_USE_BEDROCK" to "1"),
            authValidation = AuthValidation(valid = false, message = "Bedrock not configured"),
        )
    }

    @Test
    fun `preflight passes when auth fails but processEnv has ANTHROPIC_API_KEY`() {
        CliProcess.preflightChecks(
            cliPath = "/bin/echo",
            processEnv = mapOf("ANTHROPIC_API_KEY" to "sk-test"),
            authValidation = AuthValidation(valid = false, message = "No API key"),
        )
    }

    @Test
    fun `preflight passes when auth fails but processEnv has AWS_BEARER_TOKEN_BEDROCK`() {
        CliProcess.preflightChecks(
            cliPath = "/bin/echo",
            processEnv = mapOf("AWS_BEARER_TOKEN_BEDROCK" to "tok"),
            authValidation = AuthValidation(valid = false, message = "Bedrock not configured"),
        )
    }

    @Test
    fun `preflight still fails when auth fails and processEnv is empty`() {
        val exception = assertThrows(CliStartException::class.java) {
            CliProcess.preflightChecks(
                cliPath = "/bin/echo",
                processEnv = emptyMap(),
                authValidation = AuthValidation(valid = false, message = "No auth"),
            )
        }
        assertTrue(exception.message.contains("No auth"))
    }
}
