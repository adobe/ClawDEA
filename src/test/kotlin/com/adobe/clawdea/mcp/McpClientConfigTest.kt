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

import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientConfigTest {

    @Test
    fun `client config advertises a long timeout for interactive approvals`() {
        val config = buildMcpClientConfigJson(12345)

        assertTrue(config.contains(""""timeout":$MCP_CLIENT_INTERACTIVE_TIMEOUT_MS"""))
        assertTrue(config.contains("http://127.0.0.1:12345/mcp"))
    }
}
