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
package com.adobe.clawdea.knowledge.repostate

import com.adobe.clawdea.knowledge.repostate.sections.GitSectionGenerator
import com.adobe.clawdea.knowledge.repostate.sections.MavenSectionGenerator
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class RepoStateGenerator(private val project: Project) {

    private val generators: List<RepoStateSectionGenerator> = listOf(
        MavenSectionGenerator(),
        GitSectionGenerator(),
    )

    fun generate(): String {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return assemble(generators = generators, project = project, generatedAt = ts)
    }

    companion object {
        private val LOG = Logger.getInstance(RepoStateGenerator::class.java)
        private const val HEADER_PREFIX = "# Project state (auto-generated"

        fun assemble(
            generators: List<RepoStateSectionGenerator>,
            project: Project?,
            generatedAt: String,
        ): String {
            val sections = mutableListOf<RepoStateSection>()
            for (gen in generators) {
                if (project == null) continue
                try {
                    val section = gen.generate(project)
                    if (section != null) sections.add(section)
                } catch (e: Throwable) {
                    LOG.warn("REPO_STATE generator '${gen.name}' threw: ${e.message}")
                }
            }
            val sb = StringBuilder()
            sb.appendLine("$HEADER_PREFIX $generatedAt)")
            sb.appendLine()
            if (sections.isEmpty()) {
                sb.appendLine("_(no signals from any generator)_")
            } else {
                for (section in sections) {
                    sb.append(section.format())
                    sb.appendLine()
                }
            }
            return sb.toString().trimEnd() + "\n"
        }
    }
}
