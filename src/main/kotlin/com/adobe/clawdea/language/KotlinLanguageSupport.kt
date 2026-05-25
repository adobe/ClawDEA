/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.language

import com.intellij.lang.Language

object KotlinLanguageSupport : LanguageSupport {
    override val id = LanguageSupport.ID_KOTLIN

    // Lazy so headless tests can read other fields without forcing the Language
    // registry lookup (Kotlin plugin isn't on the headless test classpath).
    override val language: Language? by lazy {
        Language.findLanguageByID("kotlin")
    }
    override val displayName = "Kotlin"
    override val fileExtensions = setOf("kt", "kts")
}
