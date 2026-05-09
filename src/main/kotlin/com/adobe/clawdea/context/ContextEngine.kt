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
// src/main/kotlin/com/adobe/clawdea/context/ContextEngine.kt
package com.adobe.clawdea.context

import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Orchestrates context collection from all collectors, assembles and formats
 * the result according to a ContextProfile.
 */
@Service(Service.Level.PROJECT)
class ContextEngine(private val project: Project) {

    private val psiCollector = PsiCollector()
    private val fileCollector = FileCollector()
    private val gitCollector = GitCollector()
    private val indexCollector = IndexCollector()
    private val assembler = ContextAssembler()

    /**
     * Collect context for the given profile, returning formatted text
     * ready for inclusion in a Claude prompt.
     */
    fun gatherContext(editor: Editor, psiFile: PsiFile, profile: ContextProfile): String {
        val settings = ClawDEASettings.getInstance().state
        val allItems = mutableListOf<ContextItem>()

        if (profile.usePsi && settings.enablePsiCollector) {
            allItems.addAll(psiCollector.collect(editor, psiFile))
        }

        if (profile.useFiles) {
            allItems.addAll(fileCollector.collect(editor, psiFile, project))
        }

        if (profile.useGit && settings.enableGitCollector) {
            allItems.addAll(gitCollector.collect(project))
        }

        if (profile.useIndex) {
            allItems.addAll(indexCollector.collect(editor, psiFile, project, profile))
        }

        val kept = assembler.assemble(allItems, profile.getTokenBudget())
        return assembler.format(kept)
    }

    companion object {
        fun getInstance(project: Project): ContextEngine =
            project.getService(ContextEngine::class.java)
    }
}
