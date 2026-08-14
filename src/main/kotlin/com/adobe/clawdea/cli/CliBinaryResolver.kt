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

import com.intellij.openapi.diagnostic.Logger

/**
 * Resolves an agent CLI (`claude`, `codex`, …) to a launchable path, parameterized over the binary
 * name. This is the single implementation behind [resolveClaudeCliPath] / [resolveCodexCliPath] and
 * the gateway / `/cc` dialog paths, which had each hand-rolled the same four candidate directories —
 * two of them Unix-only, so on Windows inline completions and the `/cc` terminal failed to find
 * `claude` while the chat panel found it (Tier 4.3).
 *
 * Order: an explicit user-configured path (Windows `.ps1` shims normalized) wins; otherwise probe
 * the well-known npm / nvm / Volta / homebrew install locations; then a Windows PATH+PATHEXT search
 * or (on Unix) the login-shell `PATH` — which the JVM lacks on Finder/Dock launches, so a bare-name
 * spawn fails even when the binary is on the shell PATH; finally the bare name as a last resort.
 * [shellResolver] is injected so unit tests never spawn a login shell.
 */
object CliBinaryResolver {

    private val log = Logger.getInstance(CliBinaryResolver::class.java)

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    fun resolve(
        binary: String,
        configured: String,
        shellResolver: (String) -> String? = { CliEnvironment.resolveOnShellPath(it) },
    ): String {
        if (configured.isNotBlank() && configured != binary) {
            return normalizeWindowsShimPath(configured)
        }
        val home = System.getProperty("user.home")
        val candidates = if (isWindows()) {
            val appDataRoaming = System.getenv("APPDATA").orEmpty()
            val appDataLocal = System.getenv("LOCALAPPDATA").orEmpty()
            listOfNotNull(
                if (appDataRoaming.isNotBlank()) "$appDataRoaming\\npm\\$binary.cmd" else null,
                if (appDataLocal.isNotBlank()) "$appDataLocal\\Volta\\bin\\$binary.cmd" else null,
                "$home\\AppData\\Roaming\\npm\\$binary.cmd",
                "$home\\AppData\\Local\\Volta\\bin\\$binary.cmd",
                "$home\\.local\\bin\\$binary.cmd",
                "C:\\Program Files\\nodejs\\$binary.cmd",
            )
        } else {
            listOf(
                "$home/.local/bin/$binary",
                "$home/.nvm/versions/node/default/bin/$binary",
                "/usr/local/bin/$binary",
                "/opt/homebrew/bin/$binary",
            )
        }
        for (candidate in candidates) {
            val file = java.io.File(candidate)
            // On Windows .cmd/.bat shims canExecute() can return false for a launchable file —
            // launchability is decided by CreateProcess + PATHEXT. On Unix require the exec bit.
            val usable = if (isWindows()) file.isFile else file.canExecute()
            if (usable) {
                log.info("Resolved $binary CLI at: $candidate")
                return candidate
            }
        }
        if (isWindows()) {
            findBinaryOnWindowsPath(binary, System.getenv("PATH").orEmpty(), System.getenv("PATHEXT").orEmpty())
                ?.let {
                    log.info("Resolved $binary CLI on PATH: $it")
                    return it
                }
        } else {
            shellResolver(binary)?.let {
                log.info("Resolved $binary CLI on shell PATH: $it")
                return it
            }
        }
        return binary
    }
}
