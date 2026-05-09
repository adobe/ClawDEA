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
package com.adobe.clawdea.skills

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

sealed class SkillRoot {
    /**
     * Layout `<vendor>/<plugin>[/<version>]/skills/<skillName>/SKILL.md`.
     * `<version>` is optional — if the level right below `<plugin>` is named `skills`, it's skipped.
     */
    data class PluginCache(val path: Path) : SkillRoot()

    /**
     * Flat layout `<root>/<skillName>/SKILL.md`. Used for user- and project-scoped skill directories
     * where every discovered skill is attributed to the given synthetic plugin name.
     */
    data class Flat(val path: Path, val pluginName: String) : SkillRoot()
}

data class ScanStats(
    val skills: List<SkillInfo>,
    val rootsScanned: Int,
    val rootsMissing: Int,
    val rejectedCount: Int,
)

class SkillScanner(private val roots: List<SkillRoot>) {

    constructor(cacheDir: Path) : this(listOf(SkillRoot.PluginCache(cacheDir)))

    private val log = Logger.getInstance(SkillScanner::class.java)
    private val parser = SkillFrontmatterParser()

    fun scan(): List<SkillInfo> = scanWithStats().skills

    fun scanWithStats(): ScanStats {
        val rawSkills = mutableListOf<RawSkill>()
        var rootsScanned = 0
        var rootsMissing = 0
        var rejected = 0

        for (root in roots) {
            val path = when (root) {
                is SkillRoot.PluginCache -> root.path
                is SkillRoot.Flat -> root.path
            }
            if (!path.exists()) {
                rootsMissing++
                continue
            }
            rootsScanned++
            rejected += when (root) {
                is SkillRoot.PluginCache -> scanPluginCache(root.path, rawSkills)
                is SkillRoot.Flat -> scanFlat(root.path, root.pluginName, rawSkills)
            }
        }

        // Collapse same <plugin>:<skillName> across version directories, newest mtime wins.
        val deduped = rawSkills
            .groupBy { "${it.pluginName}:${it.name}" }
            .map { (_, dupes) -> dupes.maxByOrNull { it.lastModifiedMillis }!! }

        // When a skill name appears in both a plugin cache and a flat root
        // (user/project), the flat copy is redundant — drop it so the plugin
        // cache version (which has the CLI-recognized qualified name) wins.
        val byName = deduped.groupBy { it.name }
        val filtered = deduped.filter { raw ->
            val siblings = byName[raw.name] ?: emptyList()
            if (siblings.size <= 1) return@filter true
            val hasPluginCacheVersion = siblings.any { it.pluginName != "user" && it.pluginName != "project" }
            !hasPluginCacheVersion || (raw.pluginName != "user" && raw.pluginName != "project")
        }

        val nameCount = filtered.groupBy { it.name }.mapValues { it.value.size }

        val skills = filtered.map { raw ->
            val qualifiedName = "${raw.pluginName}:${raw.name}"
            val aliases = mutableListOf("/$qualifiedName")
            if ((nameCount[raw.name] ?: 0) <= 1) {
                aliases.add(0, "/${raw.name}")
            }
            SkillInfo(
                name = raw.name,
                qualifiedName = qualifiedName,
                description = raw.description,
                pluginName = raw.pluginName,
                pluginVersion = raw.pluginVersion,
                filePath = raw.filePath,
                aliases = aliases,
            )
        }

        return ScanStats(
            skills = skills,
            rootsScanned = rootsScanned,
            rootsMissing = rootsMissing,
            rejectedCount = rejected,
        )
    }

    private fun scanPluginCache(cacheDir: Path, out: MutableList<RawSkill>): Int {
        var rejected = 0
        for (vendorDir in listDirs(cacheDir)) {
            for (pluginDir in listDirs(vendorDir)) {
                val pluginName = pluginDir.fileName.toString()
                val versionDirs = listDirs(pluginDir).filter { it.fileName.toString() != "skills" }
                val pluginSkillsDirect = pluginDir.resolve("skills")

                if (pluginSkillsDirect.exists()) {
                    rejected += collectSkillsFromSkillsDir(pluginSkillsDirect, pluginName, "unknown", out)
                }
                for (versionDir in versionDirs) {
                    val pluginVersion = versionDir.fileName.toString()
                    val skillsDir = versionDir.resolve("skills")
                    if (!skillsDir.exists()) continue
                    rejected += collectSkillsFromSkillsDir(skillsDir, pluginName, pluginVersion, out)
                }
            }
        }
        return rejected
    }

    private fun scanFlat(root: Path, pluginName: String, out: MutableList<RawSkill>): Int =
        collectSkillsFromSkillsDir(root, pluginName, pluginVersion = "user", out)

    private fun collectSkillsFromSkillsDir(
        skillsDir: Path,
        pluginName: String,
        pluginVersion: String,
        out: MutableList<RawSkill>,
    ): Int {
        var rejected = 0
        for (skillDir in listDirs(skillsDir)) {
            val skillFile = skillDir.resolve("SKILL.md")
            if (!skillFile.exists()) continue
            try {
                val content = skillFile.readText()
                val frontmatter = parser.parse(content)
                if (frontmatter == null) {
                    rejected++
                    continue
                }
                out.add(RawSkill(
                    name = frontmatter.name,
                    description = frontmatter.description,
                    pluginName = pluginName,
                    pluginVersion = pluginVersion,
                    filePath = skillFile,
                    lastModifiedMillis = runCatching { Files.getLastModifiedTime(skillFile).toMillis() }.getOrDefault(0L),
                ))
            } catch (e: Exception) {
                log.warn("Failed to parse skill file: $skillFile", e)
                rejected++
            }
        }
        return rejected
    }

    private fun listDirs(parent: Path): List<Path> {
        if (!parent.exists()) return emptyList()
        return Files.list(parent).use { stream ->
            stream.filter { Files.isDirectory(it) }.toList()
        }
    }

    private data class RawSkill(
        val name: String,
        val description: String,
        val pluginName: String,
        val pluginVersion: String,
        val filePath: Path,
        val lastModifiedMillis: Long,
    )
}
