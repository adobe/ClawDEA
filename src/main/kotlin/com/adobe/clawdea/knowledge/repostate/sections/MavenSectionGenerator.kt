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
package com.adobe.clawdea.knowledge.repostate.sections

import com.adobe.clawdea.buildtool.maven.PomReader
import com.adobe.clawdea.knowledge.repostate.RepoStateSection
import com.adobe.clawdea.knowledge.repostate.RepoStateSectionGenerator
import com.intellij.openapi.project.Project
import java.io.File

class MavenSectionGenerator : RepoStateSectionGenerator {

    override val name = "maven"

    override fun generate(project: Project): RepoStateSection? {
        val basePath = project.basePath ?: return null
        val rootPom = File(basePath, "pom.xml")
        if (!rootPom.exists()) return null

        val moduleNames = PomReader.readModules(rootPom)
        if (moduleNames.isEmpty()) {
            val desc = PomReader.readDescription(rootPom)
            val body = "- (single module) ${desc.orEmpty()}".trimEnd()
            return RepoStateSection(heading = "Modules (Maven)", body = body)
        }

        val modulesWithDesc = moduleNames.map { mod ->
            val modPom = File(File(basePath, mod), "pom.xml")
            mod to PomReader.readDescription(modPom)
        }
        return RepoStateSection(heading = "Modules (Maven)", body = formatBody(modulesWithDesc))
    }

    companion object {
        fun formatBody(modules: List<Pair<String, String?>>): String {
            return modules.joinToString("\n") { (path, desc) ->
                if (desc.isNullOrBlank()) "- $path" else "- $path — $desc"
            }
        }
    }
}
