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

import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Shared detection helpers used by [BuildTool] implementations.
 *
 * The try/catch wrappers cover plugin teardown / project-disposal races where
 * `ModuleManager.getInstance` or `getExternalSystemId()` can throw on a Project
 * mid-disposal. Steady-state plugin code never observes these.
 */
internal object BuildToolDetection {

    fun detectedViaExternalSystem(project: Project, externalSystemId: String): Boolean {
        val modules = try {
            ModuleManager.getInstance(project).modules
        } catch (_: Throwable) {
            return false
        }
        return modules.any { module ->
            try {
                ExternalSystemModulePropertyManager.getInstance(module).getExternalSystemId() == externalSystemId
            } catch (_: Throwable) {
                false
            }
        }
    }

    /** Returns project-base direct children matching [names], in the given order. */
    fun markerFiles(project: Project, names: List<String>): List<VirtualFile> {
        val basePath = project.basePath ?: return emptyList()
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()
        return names.mapNotNull { baseDir.findChild(it) }
    }
}
