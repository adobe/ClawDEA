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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide registry of [BuildTool] implementations, keyed by [BuildTool.id].
 *
 * Populated once at project startup (see `BuildToolInitializer`). Re-registration
 * for the same id is idempotent (replaces prior entry, preserves order).
 *
 * Read paths are thread-safe. Writes happen at startup and from tests.
 */
object BuildToolRegistry {
    private val byId = ConcurrentHashMap<String, BuildTool>()
    private val orderedIds = CopyOnWriteArrayList<String>()

    fun register(buildTool: BuildTool) {
        val isNew = byId.put(buildTool.id, buildTool) == null
        if (isNew) orderedIds.add(buildTool.id)
    }

    fun all(): Collection<BuildTool> = orderedIds.mapNotNull { byId[it] }

    fun detectAll(project: Project): List<BuildTool> =
        all().filter { it.isActive(project) }

    fun detectPrimary(project: Project): BuildTool? =
        detectAll(project).firstOrNull()

    @TestOnly
    internal fun clearForTest() {
        byId.clear()
        orderedIds.clear()
    }
}
