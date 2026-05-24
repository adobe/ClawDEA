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
package com.adobe.clawdea.language.scala

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope

/**
 * Bridge to the IntelliJ Scala plugin's PSI. Registered as an optional application
 * service in `clawdea-scala.xml` — present only when `org.intellij.scala` is installed.
 *
 * Consumers (e.g. [com.adobe.clawdea.language.ScalaLanguageSupport]) acquire this via
 * `ApplicationManager.getApplication().getServiceIfCreated(ScalaPsiBridge::class.java)`
 * and null-check the result so the no-plugin case degrades gracefully.
 *
 * This interface lives in the main classpath; only its implementation
 * ([ScalaPluginPsiBridge]) references Scala-plugin types.
 */
interface ScalaPsiBridge {
    /**
     * Walk the Scala file's imports and render class/trait/object signatures from each
     * import target that resolves to a JVM type. Returns the same sentinel strings
     * Java's findRelatedTypes uses:
     *
     * - `"No imports found."` when the file has no import statements.
     * - `"No project-scope related types found in imports."` when no import target
     *   resolves to a `PsiClass` reachable in [scope].
     * - rendered signatures otherwise.
     */
    fun findRelatedTypes(
        psiFile: PsiFile,
        project: Project,
        scope: GlobalSearchScope,
    ): String?
}
