/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.buildtool

import com.adobe.clawdea.language.LanguageSupport
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy

/**
 * Hand-rolled JDK-proxy [Project] returning [basePath] from `getBasePath`. Sufficient
 * for build-tool registry/dispatch tests that never read anything else from the
 * project.
 */
internal fun stubProject(basePath: String?): Project = Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java),
) { _, method, _ ->
    when (method.name) {
        "getBasePath" -> basePath
        "toString" -> "stubProject($basePath)"
        "hashCode" -> System.identityHashCode(basePath)
        "equals" -> false
        else -> null
    }
} as Project

internal fun fakeLanguageSupport(
    id: String,
    displayName: String = id,
    fileExtensions: Set<String> = emptySet(),
    language: Language? = null,
): LanguageSupport = object : LanguageSupport {
    override val id = id
    override val language = language
    override val displayName = displayName
    override val fileExtensions = fileExtensions
}
