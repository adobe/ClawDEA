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

import java.nio.file.Path

class WikiPath(val rootDir: Path) {
    fun index(): Path = rootDir.resolve("index.md")
    fun concept(name: String): Path? = subPath("concepts", name)
    fun source(name: String): Path? = subPath("sources", name)

    private fun subPath(subdir: String, name: String): Path? {
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed.contains("..")) return null
        // Normalize: strip optional "<subdir>/" prefix and ".md" suffix so callers
        // can pass either the full markdown relative path (e.g. "concepts/cli-bridge.md")
        // or the bare slug (e.g. "cli-bridge").
        val subdirSlash = "$subdir/"
        var normalized = trimmed
        if (normalized.startsWith(subdirSlash)) {
            normalized = normalized.substring(subdirSlash.length)
        }
        if (normalized.endsWith(".md")) {
            normalized = normalized.removeSuffix(".md")
        }
        // Security check on the normalized name — no path separators or traversal
        if (normalized.contains('/') || normalized.contains('\\')) return null
        val safe = if (normalized.endsWith(".md")) normalized else "$normalized.md"
        return rootDir.resolve(subdir).resolve(safe)
    }
}
