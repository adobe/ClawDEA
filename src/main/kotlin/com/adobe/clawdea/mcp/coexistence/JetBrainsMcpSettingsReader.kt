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
package com.adobe.clawdea.mcp.coexistence

/**
 * Reads the JetBrains MCP plugin's "server enabled" setting via reflection.
 *
 * The exact settings class FQCN must be confirmed by inspecting the bundled
 * plugin jar at runtime. Until [Task 5] confirms it, this reader throws so the
 * probe returns [JetBrainsMcpStatus.Unknown] (fail-open) and ClawDEA keeps its
 * full surface registered.
 */
object JetBrainsMcpSettingsReader {

    /**
     * @return true if the JetBrains MCP server is currently enabled.
     * @throws Throwable when the underlying setting cannot be read; the caller
     *   is expected to map the throw to [JetBrainsMcpStatus.Unknown].
     */
    fun isServerEnabled(): Boolean {
        throw NotImplementedError("Settings class FQCN to be confirmed in Task 5")
    }
}
