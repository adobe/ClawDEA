/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.language

import com.intellij.lang.Language
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class LanguageSupportRegistryTest {

    // Stub Language subclasses avoid depending on IntelliJ's plugin classpath in headless tests.
    private object FakeJavaLang : Language("FAKE_JAVA")
    private object FakeKotlinLang : Language("FAKE_KOTLIN")

    private object FakeJava : LanguageSupport {
        override val language: Language = FakeJavaLang
        override val displayName = "Java"
        override val fileExtensions = setOf("java")
        override val gradleCompileTaskName = "compileJava"
    }

    private object FakeKotlin : LanguageSupport {
        override val language: Language = FakeKotlinLang
        override val displayName = "Kotlin"
        override val fileExtensions = setOf("kt", "kts")
        override val gradleCompileTaskName = "compileKotlin"
    }

    @Before fun setUp() { LanguageSupportRegistry.clearForTest() }
    @After  fun tearDown() { LanguageSupportRegistry.clearForTest() }

    @Test fun `register then forLanguage returns same instance`() {
        LanguageSupportRegistry.register(FakeJava)
        assertSame(FakeJava, LanguageSupportRegistry.forLanguage(FakeJava.language))
    }

    @Test fun `register for same language id replaces prior entry`() {
        LanguageSupportRegistry.register(FakeJava)
        val replacement = object : LanguageSupport by FakeJava {}
        LanguageSupportRegistry.register(replacement)
        assertSame(replacement, LanguageSupportRegistry.forLanguage(FakeJava.language))
    }

    @Test fun `forFileExtension kt returns kotlin support`() {
        LanguageSupportRegistry.register(FakeKotlin)
        assertSame(FakeKotlin, LanguageSupportRegistry.forFileExtension("kt"))
    }

    @Test fun `forFileExtension kts returns kotlin support`() {
        LanguageSupportRegistry.register(FakeKotlin)
        assertSame(FakeKotlin, LanguageSupportRegistry.forFileExtension("kts"))
    }

    @Test fun `forFileExtension java returns java support`() {
        LanguageSupportRegistry.register(FakeJava)
        assertSame(FakeJava, LanguageSupportRegistry.forFileExtension("java"))
    }

    @Test fun `forFileExtension unknown returns null`() {
        LanguageSupportRegistry.register(FakeJava)
        LanguageSupportRegistry.register(FakeKotlin)
        assertNull(LanguageSupportRegistry.forFileExtension("py"))
    }

    @Test fun `all returns snapshot that does not mutate registry`() {
        LanguageSupportRegistry.register(FakeJava)
        LanguageSupportRegistry.register(FakeKotlin)
        val snapshot = LanguageSupportRegistry.all().toMutableList()
        snapshot.clear()
        assertEquals(2, LanguageSupportRegistry.all().size)
    }

    @Test fun `forLanguage returns null when not registered`() {
        assertNull(LanguageSupportRegistry.forLanguage(FakeJava.language))
    }
}
