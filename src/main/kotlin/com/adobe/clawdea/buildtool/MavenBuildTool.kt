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
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.WeakHashMap

/**
 * Maven build tool adapter.
 *
 * Detection: ExternalSystem (`"Maven"`) on any module, with a marker-file fallback
 * (`pom.xml` in [Project.getBasePath]).
 *
 * Build-config files: the root `pom.xml` plus every sub-module pom reachable via
 * `<modules>` (recursive, cycle-guarded — see [PomReader.walkPomTree]).
 *
 * Compile invocation: prefers `./mvnw` when present, falls back to `mvn` on PATH.
 * Per-language dispatch is gated on plugin presence in the root pom only — Java is
 * always allowed; Kotlin requires `kotlin-maven-plugin`; Scala requires
 * `scala-maven-plugin`. The effective-pom (deep inheritance) is not resolved.
 */
object MavenBuildTool : BuildTool {
    override val id = "maven"
    override val displayName = "Maven"

    private const val MAVEN_SYSTEM_ID = "Maven"
    private val MARKER_NAMES = listOf("pom.xml")

    override fun isActive(project: Project): Boolean =
        BuildToolDetection.detectedViaExternalSystem(project, MAVEN_SYSTEM_ID) ||
            BuildToolDetection.markerFiles(project, MARKER_NAMES).isNotEmpty()

    override fun buildConfigFiles(project: Project): List<VirtualFile> {
        val rootPomVf = BuildToolDetection.markerFiles(project, MARKER_NAMES).firstOrNull() ?: return emptyList()
        val rootPomFile = File(rootPomVf.path)
        return try {
            walkPomTreeCached(project, rootPomFile)
                .mapNotNull { LocalFileSystem.getInstance().findFileByIoFile(it) }
                .ifEmpty { listOf(rootPomVf) }
        } catch (_: Throwable) {
            // Catastrophic XML parser failure — fall back to the root pom alone.
            listOf(rootPomVf)
        }
    }

    private data class PomTreeCache(val poms: List<File>, val lastModifieds: LongArray) {
        fun isFresh(): Boolean {
            if (poms.size != lastModifieds.size) return false
            for (i in poms.indices) {
                if (poms[i].lastModified() != lastModifieds[i]) return false
            }
            return true
        }
    }

    private val pomTreeCache = WeakHashMap<Project, PomTreeCache>()

    /**
     * Returns the pom tree rooted at [rootPomFile], cached per-project. Cache freshness
     * is verified by stat-ing each cached pom's `lastModified`; if any has changed the
     * tree is re-walked. Walking is the only path that re-parses XML, so chat-context
     * calls hit the stat-only fast path.
     */
    private fun walkPomTreeCached(project: Project, rootPomFile: File): List<File> {
        synchronized(pomTreeCache) {
            pomTreeCache[project]?.takeIf { it.isFresh() }?.let { return it.poms }
        }
        val poms = PomReader.walkPomTree(rootPomFile)
        val stamps = LongArray(poms.size) { poms[it].lastModified() }
        synchronized(pomTreeCache) {
            pomTreeCache[project] = PomTreeCache(poms, stamps)
        }
        return poms
    }

    override fun compileCommandFor(
        languageSupport: LanguageSupport,
        project: Project,
    ): CompileCommand? {
        val basePath = project.basePath ?: return null
        val baseDir = File(basePath)
        if (!isLanguageAllowed(languageSupport.id, File(baseDir, "pom.xml"))) return null
        val launcher = if (File(baseDir, "mvnw").exists()) "./mvnw" else "mvn"
        return CompileCommand(
            argv = listOf(launcher, "compile", "-q"),
            workingDir = baseDir,
        )
    }

    /**
     * Java is always allowed; Kotlin/Scala require the corresponding maven plugin in
     * the root pom. The effective-pom (deep inheritance) is not resolved.
     */
    private fun isLanguageAllowed(languageId: String, rootPom: File): Boolean {
        if (languageId == LanguageSupport.ID_JAVA) return true
        return try {
            when (languageId) {
                LanguageSupport.ID_KOTLIN ->
                    PomReader.hasPlugin(rootPom, "org.jetbrains.kotlin", "kotlin-maven-plugin")
                LanguageSupport.ID_SCALA ->
                    PomReader.hasPlugin(rootPom, "net.alchim31.maven", "scala-maven-plugin")
                else -> false
            }
        } catch (_: Throwable) {
            false
        }
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

}
