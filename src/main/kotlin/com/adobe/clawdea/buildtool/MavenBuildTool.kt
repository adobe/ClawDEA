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

import com.adobe.clawdea.language.LanguageSupport
import com.adobe.clawdea.mcp.PsiUtils
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Maven build tool adapter.
 *
 * Detection: ExternalSystem (`"Maven"`) on any module, with a marker-file fallback
 * (`pom.xml` in [Project.getBasePath]).
 *
 * Compile invocation: prefers `./mvnw` when present, falls back to `mvn` on PATH.
 * Kotlin support via kotlin-maven-plugin is intentionally not detected in this PR;
 * Maven + Kotlin returns null from [compileCommandFor].
 */
object MavenBuildTool : BuildTool {
    override val id = "maven"
    override val displayName = "Maven"

    private const val MAVEN_SYSTEM_ID = "Maven"

    override fun isActive(project: Project): Boolean {
        if (detectedViaExternalSystem(project)) return true
        return markerFiles(project).isNotEmpty()
    }

    override fun buildConfigFiles(project: Project): List<VirtualFile> = markerFiles(project)

    override fun compileCommandFor(
        languageSupport: LanguageSupport,
        targetFile: String,
        project: Project,
    ): CompileCommand? {
        if (languageSupport.id != "java") return null
        val basePath = project.basePath ?: return null
        val baseDir = File(basePath)
        val launcher = if (File(baseDir, "mvnw").exists()) "./mvnw" else "mvn"
        return CompileCommand(
            argv = listOf(launcher, "compile", "-q"),
            workingDir = baseDir,
        )
    }

    override fun filterDiagnostics(output: String, targetFile: String, basePath: String): String {
        val relPath = PsiUtils.toRelativePath(targetFile, basePath)
        return output.lines()
            .filter { line ->
                (line.contains(relPath) || line.contains(targetFile)) &&
                    (line.contains("[ERROR]") || line.contains("[WARNING]") || line.contains(":["))
            }
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
                ExternalSystemModulePropertyManager.getInstance(module).getExternalSystemId() == MAVEN_SYSTEM_ID
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun markerFiles(project: Project): List<VirtualFile> {
        val basePath = project.basePath ?: return emptyList()
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()
        return listOfNotNull(baseDir.findChild("pom.xml"))
    }
}
