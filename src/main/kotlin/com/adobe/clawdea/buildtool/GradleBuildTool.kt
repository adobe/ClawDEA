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
import com.intellij.openapi.project.Project
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
    private val MARKER_NAMES = listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")

    override fun isActive(project: Project): Boolean =
        BuildToolDetection.detectedViaExternalSystem(project, GRADLE_SYSTEM_ID) ||
            BuildToolDetection.markerFiles(project, MARKER_NAMES).isNotEmpty()

    override fun buildConfigFiles(project: Project): List<VirtualFile> =
        BuildToolDetection.markerFiles(project, MARKER_NAMES)

    override fun compileCommandFor(
        languageSupport: LanguageSupport,
        project: Project,
    ): CompileCommand? {
        val task = when (languageSupport.id) {
            LanguageSupport.ID_JAVA -> "compileJava"
            LanguageSupport.ID_KOTLIN -> "compileKotlin"
            LanguageSupport.ID_SCALA -> "compileScala"
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
}
