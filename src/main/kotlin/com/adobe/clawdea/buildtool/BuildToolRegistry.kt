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
package com.adobe.clawdea.buildtool

import com.intellij.openapi.project.Project
import org.jetbrains.annotations.TestOnly

/**
 * Process-wide registry of [BuildTool] implementations, keyed by [BuildTool.id].
 *
 * Populated once at project startup (see `BuildToolInitializer`). Re-registration
 * for the same id is idempotent (replaces prior entry, preserves order). Read
 * paths return an immutable snapshot rebuilt only on writes — `detectAll` runs
 * per chat-context request, so per-call list allocation matters.
 */
object BuildToolRegistry {
    private val lock = Any()
    private val byId = LinkedHashMap<String, BuildTool>()

    @Volatile
    private var snapshot: List<BuildTool> = emptyList()

    fun register(buildTool: BuildTool) = synchronized(lock) {
        byId[buildTool.id] = buildTool
        snapshot = byId.values.toList()
    }

    fun all(): List<BuildTool> = snapshot

    fun detectAll(project: Project): List<BuildTool> =
        snapshot.filter { it.isActive(project) }

    fun detectPrimary(project: Project): BuildTool? =
        snapshot.firstOrNull { it.isActive(project) }

    @TestOnly
    internal fun clearForTest() = synchronized(lock) {
        byId.clear()
        snapshot = emptyList()
    }
}
