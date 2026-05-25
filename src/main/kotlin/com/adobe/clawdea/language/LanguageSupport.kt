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
 */
interface LanguageSupport {
    /** Stable identifier in ClawDEA's namespace (lowercase). e.g. "java", "kotlin", "scala". */
    val id: String

    /**
     * IntelliJ Language when the relevant platform plugin is installed. Null when
     * the language is supported by ClawDEA but the corresponding IntelliJ plugin is
     * absent (e.g. Scala without `org.intellij.scala`).
     */
    val language: Language?

    val displayName: String
    val fileExtensions: Set<String>

    fun findRelatedTypes(
        psiFile: PsiFile,
        project: Project,
        scope: GlobalSearchScope,
    ): String? = null

    /**
     * Per-type entries derived from the file's imports — used by IndexCollector to
     * surface related types as individual ContextItems. Defaults to empty so callers
     * naturally degrade to the [findRelatedTypes] string when richer rendering is
     * available, or skip entirely otherwise.
     */
    fun enumerateRelatedTypes(
        psiFile: PsiFile,
        project: Project,
        scope: GlobalSearchScope,
    ): List<RelatedType> = emptyList()

    /** A single project-scope type referenced from the current file. */
    data class RelatedType(
        val name: String,
        /** Pre-rendered content (modifiers + signature + member previews). */
        val text: String,
    )

    companion object {
        const val ID_JAVA = "java"
        const val ID_KOTLIN = "kotlin"
        const val ID_SCALA = "scala"
    }
}
