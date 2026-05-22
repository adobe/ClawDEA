/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.language

import com.intellij.lang.Language

object KotlinLanguageSupport : LanguageSupport {
    // Lazy so headless unit tests can read other fields without triggering the
    // Language registry lookup (Kotlin plugin isn't on the headless test classpath).
    override val language: Language by lazy {
        Language.findLanguageByID("kotlin")
            ?: error("Kotlin language not registered — Kotlin plugin missing?")
    }
    override val displayName = "Kotlin"
    override val fileExtensions = setOf("kt", "kts")
    // findRelatedTypes intentionally not overridden — returns null (default).
}
