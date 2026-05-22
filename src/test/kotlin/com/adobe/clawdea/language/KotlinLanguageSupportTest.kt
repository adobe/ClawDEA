/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.language

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-unit tests for the static facts of [KotlinLanguageSupport].
 *
 * The [KotlinLanguageSupport.language] field is intentionally not exercised here
 * because it depends on IntelliJ's Kotlin plugin being registered in the JVM
 * classpath. End-to-end coverage of that field lives in fixture-based tests.
 */
class KotlinLanguageSupportTest {

    @Test fun `displayName is Kotlin`() {
        assertEquals("Kotlin", KotlinLanguageSupport.displayName)
    }

    @Test fun `fileExtensions includes kt and kts`() {
        assertEquals(setOf("kt", "kts"), KotlinLanguageSupport.fileExtensions)
    }
}
