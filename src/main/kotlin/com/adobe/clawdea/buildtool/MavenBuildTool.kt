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

import com.adobe.clawdea.buildtool.maven.PomReader
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
 * Build-config files: includes the root `pom.xml` plus every sub-module pom reachable
 * via `<modules>` declarations (recursive, cycle-guarded — see [PomReader.walkPomTree]).
 *
 * Compile invocation: prefers `./mvnw` when present, falls back to `mvn` on PATH.
 * Per-language dispatch is gated on plugin presence in the root pom — Java is always
 * allowed; Kotlin requires `kotlin-maven-plugin`; Scala requires `scala-maven-plugin`.
 * The effective-pom (deep inheritance) is intentionally not resolved — we read what
 * is literally in the root `pom.xml`.
 */
object MavenBuildTool : BuildTool {
    override val id = "maven"
    override val displayName = "Maven"

    private const val MAVEN_SYSTEM_ID = "Maven"

    override fun isActive(project: Project): Boolean {
        if (detectedViaExternalSystem(project)) return true
        return markerFiles(project).isNotEmpty()
    }

    override fun buildConfigFiles(project: Project): List<VirtualFile> {
        val rootPomVf = markerFiles(project).firstOrNull() ?: return emptyList()
        val rootPomFile = File(rootPomVf.path)
        return try {
            // Walk the module tree to surface sub-module poms. PomReader.walkPomTree
            // is forgiving — broken module references are silently skipped — but a
            // catastrophic failure (e.g. XML parser misconfiguration) still falls
            // back to the root pom alone, preserving pre-#7 behavior.
            val lfs = LocalFileSystem.getInstance()
            PomReader.walkPomTree(rootPomFile).mapNotNull { lfs.findFileByIoFile(it) }
                .ifEmpty { listOf(rootPomVf) }
        } catch (_: Throwable) {
            listOf(rootPomVf)
        }
    }

    override fun compileCommandFor(
        languageSupport: LanguageSupport,
        targetFile: String,
        project: Project,
    ): CompileCommand? {
        val basePath = project.basePath ?: return null
        val baseDir = File(basePath)
        if (languageSupport.id !in computeAllowedLanguages(File(baseDir, "pom.xml"))) return null
        val launcher = if (File(baseDir, "mvnw").exists()) "./mvnw" else "mvn"
        return CompileCommand(
            argv = listOf(launcher, "compile", "-q"),
            workingDir = baseDir,
        )
    }

    /**
     * Determines which LanguageSupport ids this build tool will dispatch a compile
     * command for. Java is always allowed. Kotlin is added when `kotlin-maven-plugin`
     * is declared in the root pom; Scala is added when `scala-maven-plugin` is
     * declared. Effective-pom (deep inheritance) is not resolved — we read the
     * literal root pom only. On any read failure, returns the conservative default
     * `{"java"}` to preserve pre-#7 behavior.
     */
    private fun computeAllowedLanguages(rootPom: File): Set<String> {
        val langs = mutableSetOf("java")
        try {
            if (PomReader.hasPlugin(rootPom, "org.jetbrains.kotlin", "kotlin-maven-plugin")) langs += "kotlin"
            if (PomReader.hasPlugin(rootPom, "net.alchim31.maven", "scala-maven-plugin")) langs += "scala"
        } catch (_: Throwable) {
            // Conservative fallback — java only.
        }
        return langs
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
