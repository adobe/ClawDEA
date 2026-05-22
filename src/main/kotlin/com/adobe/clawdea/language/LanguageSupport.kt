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
package com.adobe.clawdea.language

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope

/**
 * Per-language hooks consumed by MCP tools and the context engine.
 * Implementations are registered with [LanguageSupportRegistry] at project startup.
 *
 * Behavior-preserving seam: every method exists to host code that already lives somewhere
 * in the codebase today, just routed through this seam.
 */
interface LanguageSupport {
    val language: Language
    val displayName: String
    val fileExtensions: Set<String>
    val gradleCompileTaskName: String?

    fun isFileInLanguage(psiFile: PsiFile): Boolean =
        psiFile.language.id == language.id

    fun findRelatedTypes(
        psiFile: PsiFile,
        project: Project,
        scope: GlobalSearchScope,
    ): String? = null
}
