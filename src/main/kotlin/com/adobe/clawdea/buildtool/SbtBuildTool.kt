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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * sbt build tool adapter.
 *
 * Detection: ExternalSystem (`"SBT"` — uppercase, the IntelliJ Scala plugin's
 * conventional id) on any module, with a marker-file fallback (`build.sbt` in
 * [Project.getBasePath]).
 *
 * Compile invocation: `sbt -batch -no-colors compile`. Requires `sbt` on PATH.
 * If `sbt` is missing the subprocess fails at start; we surface the same error
 * envelope as Gradle/Maven do when their launchers are missing.
 *
 * Supports both `.scala` (LanguageSupport id `"scala"`) and `.java`
 * (LanguageSupport id `"java"`) — sbt's `compile` task compiles both source
 * trees together.
 */
object SbtBuildTool : BuildTool {
    override val id = "sbt"
    override val displayName = "sbt"

    private const val SBT_SYSTEM_ID = "SBT"
    private val MARKER_NAMES = listOf("build.sbt")

    override fun isActive(project: Project): Boolean =
        BuildToolDetection.detectedViaExternalSystem(project, SBT_SYSTEM_ID) ||
            BuildToolDetection.markerFiles(project, MARKER_NAMES).isNotEmpty()

    override fun buildConfigFiles(project: Project): List<VirtualFile> {
        val basePath = project.basePath ?: return emptyList()
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()
        val configs = mutableListOf<VirtualFile>()
        baseDir.findChild("build.sbt")?.let { configs.add(it) }
        baseDir.findChild("project")?.let { projectDir ->
            if (projectDir.isDirectory) {
                projectDir.findChild("build.properties")?.let { configs.add(it) }
                projectDir.findChild("plugins.sbt")?.let { configs.add(it) }
            }
        }
        return configs
    }

    override fun compileCommandFor(
        languageSupport: LanguageSupport,
        project: Project,
    ): CompileCommand? {
        if (languageSupport.id != LanguageSupport.ID_SCALA && languageSupport.id != LanguageSupport.ID_JAVA) return null
        val basePath = project.basePath ?: return null
        return CompileCommand(
            argv = listOf("sbt", "-batch", "-no-colors", "compile"),
            workingDir = File(basePath),
        )
    }

    override fun filterDiagnostics(output: String, targetFile: String, basePath: String): String {
        val relPath = PsiUtils.toRelativePath(targetFile, basePath)
        return output.lines()
            .filter { line ->
                (line.contains(relPath) || line.contains(targetFile)) &&
                    (line.contains("[error]") || line.contains("[warn]") || line.contains("[info]"))
            }
            .joinToString("\n")
    }

}
