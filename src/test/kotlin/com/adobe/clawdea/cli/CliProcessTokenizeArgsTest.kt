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

import kotlin.test.Test
import kotlin.test.assertEquals

class CliProcessTokenizeArgsTest {

    @Test fun `simple space-separated args`() {
        assertEquals(listOf("--flag", "value"), CliProcess.tokenizeArgs("--flag value"))
    }

    @Test fun `quoted value with spaces`() {
        assertEquals(listOf("--msg", "hello world"), CliProcess.tokenizeArgs("""--msg "hello world""""))
    }

    @Test fun `multiple quoted args`() {
        assertEquals(
            listOf("--a", "one two", "--b", "three four"),
            CliProcess.tokenizeArgs("""--a "one two" --b "three four""""),
        )
    }

    @Test fun `empty string returns empty list`() {
        assertEquals(emptyList(), CliProcess.tokenizeArgs(""))
    }

    @Test fun `extra whitespace is collapsed`() {
        assertEquals(listOf("--flag", "val"), CliProcess.tokenizeArgs("  --flag   val  "))
    }

    @Test fun `single unquoted arg`() {
        assertEquals(listOf("--verbose"), CliProcess.tokenizeArgs("--verbose"))
    }

    @Test fun `empty quotes are absorbed`() {
        assertEquals(listOf("--key"), CliProcess.tokenizeArgs("""--key """""))
    }

    @Test fun `extra args drop permission allowlist flags managed by ClawDEA`() {
        assertEquals(
            listOf("--model", "sonnet"),
            CliProcess.sanitizeCliExtraArgs(listOf(
                "--model", "sonnet",
                "--allowedTools", "Read",
                "--allowed-tools=Bash(git *)",
                "--permission-mode=auto",
                "--permission-prompt-tool", "custom_tool",
                "--disallowedTools=Bash",
                "--disallowed-tools", "Write",
            )),
        )
    }

    @Test fun `extra args drop dangerous skip permissions flag`() {
        assertEquals(
            listOf("--model", "sonnet"),
            CliProcess.sanitizeCliExtraArgs(listOf(
                "--dangerously-skip-permissions",
                "--allow-dangerously-skip-permissions",
                "--model", "sonnet",
            )),
        )
    }

    @Test fun `extra args drop settings sources that could reintroduce project permission allowlists`() {
        assertEquals(
            listOf("--model", "sonnet"),
            CliProcess.sanitizeCliExtraArgs(listOf(
                "--setting-sources", "user,project,local",
                "--settings=./custom-settings.json",
                "--model", "sonnet",
            )),
        )
    }
}
