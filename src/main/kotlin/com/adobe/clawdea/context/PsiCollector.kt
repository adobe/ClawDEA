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
package com.adobe.clawdea.context

import com.adobe.clawdea.util.runReadAction

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * Collects semantic context from IntelliJ's PSI (Program Structure Interface).
 * All reads happen inside ReadAction — safe to call from any thread.
 */
class PsiCollector {

    fun collect(editor: Editor, psiFile: PsiFile): List<ContextItem> {
        return runReadAction {
            val items = mutableListOf<ContextItem>()
            val offset = editor.caretModel.offset

            val element = psiFile.findElementAt(offset)
            val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
            val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)

            // Current class signature + body
            if (containingClass != null) {
                items.add(ContextItem(
                    label = "Current class: ${containingClass.name}",
                    content = containingClass.text,
                    score = 1.0,
                    source = "psi"
                ))

                // Supertypes
                val supertypes = collectSupertypes(containingClass)
                if (supertypes.isNotBlank()) {
                    items.add(ContextItem(
                        label = "Supertypes",
                        content = supertypes,
                        score = 0.8,
                        source = "psi"
                    ))
                }
            }

            // Method at caret
            if (containingMethod != null) {
                items.add(ContextItem(
                    label = "Method at caret: ${containingMethod.name}",
                    content = containingMethod.text,
                    score = 0.95,
                    source = "psi"
                ))
            }

            // Imports
            if (psiFile is PsiJavaFile) {
                val importList = psiFile.importList
                if (importList != null && importList.allImportStatements.isNotEmpty()) {
                    val importText = importList.allImportStatements.joinToString("\n") { it.text }
                    items.add(ContextItem(
                        label = "Imports",
                        content = importText,
                        score = 0.6,
                        source = "psi"
                    ))
                }
            }

            items
        }
    }

    private fun collectSupertypes(psiClass: PsiClass): String {
        val lines = mutableListOf<String>()

        // Check extends clause using extendsList (works in test environment)
        val extendsList = psiClass.extendsList
        if (extendsList != null) {
            for (extendsType in extendsList.referencedTypes) {
                val typeText = extendsType.canonicalText
                if (typeText != "java.lang.Object") {
                    lines.add("extends $typeText")
                    // Try to resolve the superclass for method details
                    val superClass = psiClass.superClass
                    if (superClass != null && superClass.methods.isNotEmpty()) {
                        for (method in superClass.methods) {
                            lines.add("  ${formatMethodSignature(method)}")
                        }
                    }
                }
            }
        }

        // Check implements clause using implementsList (works in test environment)
        val implementsList = psiClass.implementsList
        if (implementsList != null) {
            for (implementsType in implementsList.referencedTypes) {
                val typeText = implementsType.canonicalText
                lines.add("implements $typeText")
                // Try to resolve interfaces for method details
                for (iface in psiClass.interfaces) {
                    if (iface.qualifiedName == typeText || iface.name == typeText) {
                        if (iface.methods.isNotEmpty()) {
                            for (method in iface.methods) {
                                lines.add("  ${formatMethodSignature(method)}")
                            }
                        }
                        break
                    }
                }
            }
        }

        return lines.joinToString("\n")
    }

    private fun formatMethodSignature(method: PsiMethod): String {
        val returnType = method.returnType?.presentableText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") {
            "${it.type.presentableText} ${it.name}"
        }
        val annotations = method.annotations.joinToString(" ") { "@${it.qualifiedName?.substringAfterLast('.')}" }
        val prefix = if (annotations.isNotBlank()) "$annotations " else ""
        return "${prefix}${returnType} ${method.name}($params)"
    }
}
