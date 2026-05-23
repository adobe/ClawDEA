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
 * Process-wide registry of [LanguageSupport] implementations, keyed by [LanguageSupport.id]
 * (ClawDEA's stable namespace — e.g. `"java"`, `"kotlin"`, `"scala"`).
 *
 * Populated once at plugin initialization (see [LanguageSupportInitializer]).
 * Re-registration for the same id is idempotent (replaces prior entry).
 *
 * Read paths are thread-safe. Writes happen at startup and from tests.
 *
 * Initialization-order note: callers that may run before [LanguageSupportInitializer]
 * (e.g. CandidateFingerprinter during indexing) must read the registry lazily, not
 * eagerly at field init time, to avoid an empty snapshot.
 */
object LanguageSupportRegistry {
    private val byId = ConcurrentHashMap<String, LanguageSupport>()

    fun register(support: LanguageSupport) {
        byId[support.id] = support
    }

    /** Look up by IntelliJ Language. Returns null if no registered support reports a matching language. */
    fun forLanguage(language: Language): LanguageSupport? =
        byId.values.firstOrNull { it.language?.id == language.id }

    /**
     * Look up the LanguageSupport for a PSI file. Tries Language identity first; falls back
     * to file extension matching when no Language matches. The fallback is necessary for
     * IntelliJ Languages whose id differs from our registered LanguageSupport's id — e.g.
     * Scala 3 (`psiFile.language.id == "Scala 3"`) parsed by the same Scala plugin whose
     * Scala 2 Language has id `"Scala"`.
     */
    fun forPsiFile(psiFile: PsiFile): LanguageSupport? {
        forLanguage(psiFile.language)?.let { return it }
        val ext = psiFile.virtualFile?.extension ?: return null
        return forFileExtension(ext)
    }

    fun forFileExtension(extension: String): LanguageSupport? =
        byId.values.firstOrNull { extension in it.fileExtensions }

    fun all(): Collection<LanguageSupport> = byId.values.toList()

    @TestOnly
    internal fun clearForTest() = byId.clear()
}
