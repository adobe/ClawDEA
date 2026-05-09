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
package com.adobe.clawdea.knowledge.workspace

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Walks up from a project root looking for the workspace manifest file
 * (.clawdea-workspace.md). Nearest match wins when nested workspaces
 * exist — same model as .editorconfig / .gitignore.
 */
object WorkspaceDiscovery {

    const val DEFAULT_MANIFEST_NAME = ".clawdea-workspace.md"

    private val LOG = Logger.getInstance(WorkspaceDiscovery::class.java)

    /**
     * Walk up from start, checking each ancestor for manifestName.
     * Returns the path to the manifest when found, or null otherwise.
     * Stops at the filesystem root.
     */
    fun discover(start: Path, manifestName: String = DEFAULT_MANIFEST_NAME): Path? {
        var dir: Path? = start.toAbsolutePath().normalize()
        while (dir != null) {
            val candidate = dir.resolve(manifestName)
            if (Files.isRegularFile(candidate)) return candidate
            dir = dir.parent
        }
        return null
    }

    /**
     * Convenience: read and parse the manifest at manifestPath. Returns
     * null if the file is missing or unreadable.
     */
    fun parseManifest(manifestPath: Path): WorkspaceManifest? {
        if (!Files.isRegularFile(manifestPath)) return null
        return try {
            val text = Files.readString(manifestPath)
            WorkspaceManifestParser.parse(text, discoveredAt = manifestPath.parent)
        } catch (e: Throwable) {
            LOG.warn("WorkspaceDiscovery.parseManifest failed for $manifestPath", e)
            null
        }
    }
}
