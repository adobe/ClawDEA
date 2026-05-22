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

import com.adobe.clawdea.mcp.PsiUtils
import com.intellij.lang.Language
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Gradle build tool adapter.
 *
 * Detection: ExternalSystem (`"GRADLE"`) on any module, with a marker-file fallback
 * (`build.gradle{.kts}` / `settings.gradle{.kts}` in [Project.getBasePath]).
 *
 * Compile invocation: always `./gradlew <task> --quiet` in the project base path.
 * If the wrapper script is absent this will fail at subprocess start; falling back
 * to a system-PATH `gradle` would introduce JDK/version ambiguity and is out of
 * scope for this adapter.
 */
object GradleBuildTool : BuildTool {
    override val id = "gradle"
    override val displayName = "Gradle"

    private const val GRADLE_SYSTEM_ID = "GRADLE"

    override fun isActive(project: Project): Boolean {
        if (detectedViaExternalSystem(project)) return true
        return markerFiles(project).isNotEmpty()
    }

    override fun buildConfigFiles(project: Project): List<VirtualFile> = markerFiles(project)

    override fun compileCommandFor(
        language: Language,
        targetFile: String,
        project: Project,
    ): CompileCommand? {
        val task = when (language.id) {
            "JAVA" -> "compileJava"
            "kotlin" -> "compileKotlin"
            else -> return null
        }
        val basePath = project.basePath ?: return null
        return CompileCommand(
            argv = listOf("./gradlew", task, "--quiet"),
            workingDir = File(basePath),
        )
    }

    override fun filterDiagnostics(output: String, targetFile: String, basePath: String): String {
        val relPath = PsiUtils.toRelativePath(targetFile, basePath)
        return output.lines()
            .filter { it.contains(relPath) || it.contains(targetFile) }
            .joinToString("\n")
    }

    private fun detectedViaExternalSystem(project: Project): Boolean {
        val modules = try {
            ModuleManager.getInstance(project).modules
        } catch (_: Throwable) {
            return false
        }
        return modules.any { module ->
            try {
                ExternalSystemModulePropertyManager.getInstance(module).getExternalSystemId() == GRADLE_SYSTEM_ID
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun markerFiles(project: Project): List<VirtualFile> {
        val basePath = project.basePath ?: return emptyList()
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()
        val names = listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        return names.mapNotNull { baseDir.findChild(it) }
    }
}
