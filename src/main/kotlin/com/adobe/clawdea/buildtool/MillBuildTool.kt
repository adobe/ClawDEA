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
 * Mill build tool adapter.
 *
 * Detection: marker files only (`build.mill`, `build.mill.scala`, or `build.sc`).
 * Mill does not have a dedicated IntelliJ ExternalSystem id — its IDE integration
 * goes through generic BSP, which is also used by sbt and Bloop and is therefore
 * too coarse a signal to attribute to Mill specifically.
 *
 * Compile invocation: `./mill --no-server __.compile` if a `mill` wrapper script is
 * present in the project base, else `mill --no-server __.compile` on PATH. `__` is
 * Mill's recursive task selector — compiles all modules without needing names.
 * `--no-server` keeps each invocation stateless (no Mill background server).
 *
 * Supports both `.scala` (LanguageSupport id `"scala"`) and `.java`
 * (LanguageSupport id `"java"`). Mill compiles them in the same task.
 */
object MillBuildTool : BuildTool {
    override val id = "mill"
    override val displayName = "Mill"

    private val MARKER_NAMES = listOf("build.mill", "build.mill.scala", "build.sc")

    override fun isActive(project: Project): Boolean =
        BuildToolDetection.markerFiles(project, MARKER_NAMES).isNotEmpty()

    override fun buildConfigFiles(project: Project): List<VirtualFile> =
        BuildToolDetection.markerFiles(project, MARKER_NAMES)

    override fun compileCommandFor(
        languageSupport: LanguageSupport,
        project: Project,
    ): CompileCommand? {
        if (languageSupport.id != LanguageSupport.ID_SCALA && languageSupport.id != LanguageSupport.ID_JAVA) return null
        val basePath = project.basePath ?: return null
        val baseDir = File(basePath)
        val launcher = if (File(baseDir, "mill").canExecute()) "./mill" else "mill"
        return CompileCommand(
            argv = listOf(launcher, "--no-server", "__.compile"),
            workingDir = baseDir,
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
