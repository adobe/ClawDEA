/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.language

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Registers built-in [LanguageSupport] implementations on first project open.
 *
 * The registry is process-wide, so re-running for additional projects is idempotent
 * (register replaces by language id).
 */
class LanguageSupportInitializer : ProjectActivity {
    override suspend fun execute(project: Project) {
        LanguageSupportRegistry.register(JavaLanguageSupport)
        LanguageSupportRegistry.register(KotlinLanguageSupport)
    }
}
