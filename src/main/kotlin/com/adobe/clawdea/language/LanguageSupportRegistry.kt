/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.language

import com.intellij.lang.Language
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of [LanguageSupport] implementations, keyed by IntelliJ Language ID.
 *
 * Populated once at plugin initialization (see [LanguageSupportInitializer]).
 * Re-registration for the same language ID is idempotent (replaces prior entry).
 *
 * Read paths are thread-safe. Writes happen at startup and from tests.
 *
 * Initialization-order note: callers that may run before [LanguageSupportInitializer]
 * (e.g. CandidateFingerprinter during indexing) must read the registry lazily, not
 * eagerly at field init time, to avoid an empty snapshot.
 */
object LanguageSupportRegistry {
    private val byLanguageId = ConcurrentHashMap<String, LanguageSupport>()

    fun register(support: LanguageSupport) {
        byLanguageId[support.language.id] = support
    }

    fun forLanguage(language: Language): LanguageSupport? = byLanguageId[language.id]
    fun forPsiFile(psiFile: PsiFile): LanguageSupport? = byLanguageId[psiFile.language.id]
    fun forFileExtension(extension: String): LanguageSupport? =
        byLanguageId.values.firstOrNull { extension in it.fileExtensions }
    fun all(): Collection<LanguageSupport> = byLanguageId.values.toList()

    @TestOnly
    internal fun clearForTest() = byLanguageId.clear()
}
