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

import com.adobe.clawdea.language.scala.ScalaPsiBridge
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope

/**
 * Generic Scala support — does not depend on the optional `org.intellij.scala` plugin
 * for file recognition or build-tool dispatch. When the Scala plugin is installed,
 * [findRelatedTypes] delegates to [ScalaPsiBridge] via an optional application service
 * registered in `clawdea-scala.xml` (sub-project #4). When it isn't, [findRelatedTypes]
 * returns null and the caller renders the generic "not supported" message.
 */
object ScalaLanguageSupport : LanguageSupport {
    override val id = "scala"
    override val displayName = "Scala"
    override val fileExtensions = setOf("scala", "sc")
    override val language: Language? by lazy {
        Language.findLanguageByID("Scala")
    }

    /**
     * Delegates to [ScalaPsiBridge] when the Scala plugin is installed; returns null
     * otherwise. `getServiceIfCreated` is null-safe for unregistered services.
     */
    override fun findRelatedTypes(
        psiFile: PsiFile,
        project: Project,
        scope: GlobalSearchScope,
    ): String? {
        return ApplicationManager.getApplication()
            ?.getServiceIfCreated(ScalaPsiBridge::class.java)
            ?.findRelatedTypes(psiFile, project, scope)
    }
}
