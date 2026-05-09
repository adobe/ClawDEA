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
// src/main/kotlin/com/adobe/clawdea/context/FileCollector.kt
package com.adobe.clawdea.context

import com.adobe.clawdea.util.runReadAction

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Collects workspace-level context: open editors, project structure, build config.
 */
class FileCollector {

    fun collect(editor: Editor, psiFile: PsiFile, project: Project): List<ContextItem> {
        return runReadAction {
            val items = mutableListOf<ContextItem>()

            // Current file (full content)
            items.add(ContextItem(
                label = "Current file: ${psiFile.name}",
                content = psiFile.text,
                score = 1.0,
                source = "file"
            ))

            // Open editor tabs (other than current)
            val fileEditorManager = FileEditorManager.getInstance(project)
            val psiManager = PsiManager.getInstance(project)
            val openFiles = fileEditorManager.openFiles.filter { it != psiFile.virtualFile }
            for ((index, vf) in openFiles.take(10).withIndex()) {
                val openPsi = psiManager.findFile(vf) ?: continue
                val score = 0.7 - (index * 0.05) // Decreasing relevance
                items.add(ContextItem(
                    label = "Open file: ${vf.name}",
                    content = openPsi.text,
                    score = score.coerceAtLeast(0.3),
                    source = "file"
                ))
            }

            // Project structure overview
            val structureLines = mutableListOf<String>()
            val rootManager = ProjectRootManager.getInstance(project)
            for (root in rootManager.contentSourceRoots) {
                structureLines.add("Source root: ${root.path}")
            }
            if (structureLines.isNotEmpty()) {
                items.add(ContextItem(
                    label = "Project structure",
                    content = structureLines.joinToString("\n"),
                    score = 0.3,
                    source = "file"
                ))
            }

            // Build config (pom.xml or build.gradle)
            val projectDir = project.basePath?.let {
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it)
            }
            if (projectDir != null) {
                val buildFile = projectDir.findChild("pom.xml")
                    ?: projectDir.findChild("build.gradle")
                    ?: projectDir.findChild("build.gradle.kts")
                if (buildFile != null) {
                    val buildPsi = psiManager.findFile(buildFile)
                    if (buildPsi != null) {
                        items.add(ContextItem(
                            label = "Build config: ${buildFile.name}",
                            content = buildPsi.text,
                            score = 0.4,
                            source = "file"
                        ))
                    }
                }
            }

            items
        }
    }
}
