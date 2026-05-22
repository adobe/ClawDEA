/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.language

import com.intellij.lang.Language

/**
 * Generic Scala support — does not depend on the optional `org.intellij.scala` plugin.
 *
 * When the Scala plugin is installed, [language] resolves to the platform-registered
 * Scala Language; when it isn't, [language] is null and Scala diagnostics still work via
 * `GradleBuildTool.compileCommandFor` (which dispatches on [id], not on the Language).
 *
 * `findRelatedTypes` returns null (the default). Real Scala PSI walking is sub-project #4.
 */
object ScalaLanguageSupport : LanguageSupport {
    override val id = "scala"
    override val displayName = "Scala"
    override val fileExtensions = setOf("scala", "sc")
    override val language: Language? by lazy {
        Language.findLanguageByID("Scala")
    }
}
