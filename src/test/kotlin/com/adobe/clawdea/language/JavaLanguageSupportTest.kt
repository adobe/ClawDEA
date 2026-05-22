/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.language

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-unit tests for the static facts of [JavaLanguageSupport].
 *
 * The [JavaLanguageSupport.language] field is intentionally not exercised here
 * because it depends on IntelliJ's Java plugin being registered in the JVM classpath.
 * findRelatedTypes is exercised end-to-end via IndexQueryHandlerTest in the fixture
 * runner, since it requires PsiJavaFile + real imports + GlobalSearchScope.
 */
class JavaLanguageSupportTest {

    @Test fun `id is java`() {
        assertEquals("java", JavaLanguageSupport.id)
    }

    @Test fun `displayName is Java`() {
        assertEquals("Java", JavaLanguageSupport.displayName)
    }

    @Test fun `fileExtensions is just java`() {
        assertEquals(setOf("java"), JavaLanguageSupport.fileExtensions)
    }
}
