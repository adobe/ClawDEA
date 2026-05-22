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

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * A build tool that ClawDEA can invoke for compile-based diagnostics, and whose
 * build-configuration files contribute to primer context.
 *
 * Implementations are registered with [BuildToolRegistry] at project startup
 * (see `BuildToolInitializer`). Registration is process-wide; activeness is
 * per-project via [isActive].
 */
interface BuildTool {
    val id: String
    val displayName: String

    /** True if this build tool applies to [project] (ExternalSystem ∪ marker-file fallback). */
    fun isActive(project: Project): Boolean

    /** Build-configuration files this tool contributes to primer/context. */
    fun buildConfigFiles(project: Project): List<VirtualFile>

    /**
     * Returns the compile command for [targetFile] in [language], or null if
     * this build tool does not support compiling that language in [project].
     */
    fun compileCommandFor(language: Language, targetFile: String, project: Project): CompileCommand?

    /**
     * Filters captured stdout/stderr to lines relevant to [targetFile].
     * [basePath] is the project base path for resolving relative paths.
     * Returns the filtered text, or an empty string if nothing matched.
     */
    fun filterDiagnostics(output: String, targetFile: String, basePath: String): String
}
