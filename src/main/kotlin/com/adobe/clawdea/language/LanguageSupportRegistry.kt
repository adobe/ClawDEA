/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.language

import com.intellij.lang.Language
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.TestOnly

/**
 * Process-wide registry of [LanguageSupport] implementations, keyed by
 * [LanguageSupport.id]. Populated once at plugin initialization (see
 * [LanguageSupportInitializer]); re-registration is idempotent.
 *
 * Read paths return lock-free snapshots so per-file scanning hot paths
 * (e.g. CandidateFingerprinter) hit O(1) extension/language indexes.
 *
 * Initialization-order note: callers that may run before
 * [LanguageSupportInitializer] (e.g. CandidateFingerprinter during indexing) must
 * read lazily, not eagerly at field init time.
 */
object LanguageSupportRegistry {
    private val lock = Any()
    private val byId = LinkedHashMap<String, LanguageSupport>()

    @Volatile private var byLanguageIdSnapshot: Map<String, LanguageSupport> = emptyMap()
    @Volatile private var byExtensionSnapshot: Map<String, LanguageSupport> = emptyMap()
    @Volatile private var allSnapshot: List<LanguageSupport> = emptyList()

    fun register(support: LanguageSupport) = synchronized(lock) {
        byId[support.id] = support
        rebuildSnapshots()
    }

    /** Look up by IntelliJ Language. Returns null if no registered support matches. */
    fun forLanguage(language: Language): LanguageSupport? =
        byLanguageIdSnapshot[language.id]

    /**
     * Look up the LanguageSupport for a PSI file. Tries Language identity first;
     * falls back to file extension. The fallback is needed for IntelliJ Languages
     * whose id differs from our registered LanguageSupport id — e.g. Scala 3
     * (`"Scala 3"`) parsed by the same plugin whose Scala 2 Language id is `"Scala"`.
     */
    fun forPsiFile(psiFile: PsiFile): LanguageSupport? {
        forLanguage(psiFile.language)?.let { return it }
        val ext = psiFile.virtualFile?.extension ?: return null
        return forFileExtension(ext)
    }

    fun forFileExtension(extension: String): LanguageSupport? =
        byExtensionSnapshot[extension]

    fun all(): Collection<LanguageSupport> = allSnapshot

    private fun rebuildSnapshots() {
        val all = byId.values.toList()
        allSnapshot = all
        byLanguageIdSnapshot = all.mapNotNull { s -> s.language?.id?.let { it to s } }.toMap()
        byExtensionSnapshot = buildMap {
            for (s in all) for (ext in s.fileExtensions) put(ext, s)
        }
    }

    @TestOnly
    internal fun clearForTest() = synchronized(lock) {
        byId.clear()
        rebuildSnapshots()
    }
}
