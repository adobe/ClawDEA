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
package com.adobe.clawdea.knowledge.notes

import com.adobe.clawdea.util.ClaudeProjectDir
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths

object NotesPaths {
    fun notesDir(project: Project): Path? {
        val basePath = project.basePath ?: return null
        return notesDirFor(Paths.get(System.getProperty("user.home")), basePath)
    }

    fun currentMd(project: Project): Path? = notesDir(project)?.resolve("CURRENT.md")

    fun resolveNoteFile(project: Project, arg: String): Path? {
        val basePath = project.basePath ?: return null
        return resolveNoteFile(Paths.get(System.getProperty("user.home")), basePath, arg)
    }

    internal fun notesDirFor(home: Path, projectBasePath: String): Path {
        val encodedPath = ClaudeProjectDir.encode(projectBasePath)
        return home.resolve(".claude").resolve("projects").resolve(encodedPath).resolve("notes")
    }

    internal fun currentMdFor(home: Path, projectBasePath: String): Path =
        notesDirFor(home, projectBasePath).resolve("CURRENT.md")

    internal fun resolveNoteFile(home: Path, projectBasePath: String, arg: String): Path? {
        if (arg.isBlank()) return null
        if (arg.contains("\\")) return null
        val notesDir = notesDirFor(home, projectBasePath)
        val candidate = notesDir.resolve(arg).normalize()
        if (!candidate.startsWith(notesDir.normalize())) return null
        return candidate
    }
}
