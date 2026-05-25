/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.language

import com.adobe.clawdea.language.scala.ScalaPsiBridge
import com.intellij.openapi.application.ApplicationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-unit tests for the static facts of [ScalaLanguageSupport].
 *
 * The [ScalaLanguageSupport.language] field is non-null only when the IntelliJ Scala
 * plugin is registered in the JVM classpath, which is generally not the case in the
 * headless test environment. The "language is null or has id Scala" assertion handles
 * both possibilities.
 */
class ScalaLanguageSupportTest {

    @Test fun `id is scala`() {
        assertEquals("scala", ScalaLanguageSupport.id)
    }

    @Test fun `displayName is Scala`() {
        assertEquals("Scala", ScalaLanguageSupport.displayName)
    }

    @Test fun `fileExtensions includes scala and sc`() {
        assertEquals(setOf("scala", "sc"), ScalaLanguageSupport.fileExtensions)
    }

    @Test fun `language is null or has id Scala`() {
        val lang = ScalaLanguageSupport.language
        if (lang != null) {
            assertEquals("Scala", lang.id)
        }
        // Either is acceptable — depends on whether the Scala plugin is on the classpath.
        assertTrue(lang == null || lang.id == "Scala")
    }

    @Test fun `ScalaPsiBridge service lookup degrades to null when Scala plugin is absent`() {
        // In the headless test environment, the Scala plugin isn't loaded and
        // clawdea-scala.xml isn't applied. The production code wraps the lookup in
        // try-catch because IntelliJ versions differ — some return null for an
        // unregistered service, others throw. Mirror that here: assert the wrapper
        // returns null either way.
        val app = ApplicationManager.getApplication()
        val service = try {
            app?.getService(ScalaPsiBridge::class.java)
        } catch (_: Throwable) {
            null
        }
        assertNull(
            "ScalaPsiBridge service should not be registered without the Scala plugin on the test classpath.",
            service,
        )
    }
}
