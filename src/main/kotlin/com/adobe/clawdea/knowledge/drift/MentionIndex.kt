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
package com.adobe.clawdea.knowledge.drift

/**
 * False-positive-controlled "what does this wiki page mention" extractor.
 *
 * A token is considered a valid mention only if it is one of:
 *  - a project-relative path under `src/`, `bin/main/`, or one of the
 *    canonical Gradle build files (`build.gradle.kts`, `settings.gradle.kts`,
 *    `gradle.properties`)
 *  - a basename of length >= 6 with at least one uppercase letter (rules out
 *    `id`, `Path`, `List`, etc.)
 *  - a class/object/interface/enum/data-class/sealed-class name extracted
 *    from a `.kt` or `.java` file (used by [CommitWikiDriftDetector] when
 *    indexing touched source files into mention candidates).
 */
object MentionIndex {

    private val PATH_PREFIXES = listOf("src/", "bin/main/")
    private val EXACT_BUILD_FILES = setOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties")
    private val BASENAME_RX = Regex("""[A-Za-z][A-Za-z0-9_-]{5,}""")
    private val UPPER_RX = Regex("""[A-Z]""")
    private val KT_DECL_RX = Regex(
        """^\s*(?:public|private|internal|open|sealed|abstract|final|data)?\s*(?:class|object|interface|enum\s+class)\s+([A-Z][A-Za-z0-9_]+)""",
        RegexOption.MULTILINE,
    )

    /**
     * Returns true if [token] meets at least one of the validity rules.
     */
    fun isValidToken(token: String): Boolean {
        if (token.isBlank()) return false
        if (token in EXACT_BUILD_FILES) return true
        if (PATH_PREFIXES.any { token.startsWith(it) }) return true
        return token.length >= 6 && UPPER_RX.containsMatchIn(token) && !token.contains('/') && !token.contains('.')
    }

    /**
     * Extract class/object/interface/enum-class/data-class/sealed-class names
     * from a Kotlin or Java source file's text.
     */
    fun extractKtClassNames(source: String): Set<String> =
        KT_DECL_RX.findAll(source).map { it.groupValues[1] }.toSet()

    /**
     * Pull every "valid" identifier-like token out of a markdown page so the
     * detector can intersect against touched paths and class names.
     */
    fun buildForPage(markdown: String): Set<String> {
        val out = mutableSetOf<String>()
        // Path tokens — anything matching src/.../*.{kt,java,md} or build files.
        Regex("""[A-Za-z0-9_./-]+""").findAll(markdown).forEach { match ->
            val token = match.value
            if (isValidToken(token)) out += token
        }
        // Identifier tokens — bare PascalCase names (e.g. `WikiAgentsArg`).
        BASENAME_RX.findAll(markdown).forEach { match ->
            val token = match.value
            if (isValidToken(token)) out += token
        }
        return out
    }
}
