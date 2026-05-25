/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.language

import com.adobe.clawdea.mcp.PsiUtils
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope

object JavaLanguageSupport : LanguageSupport {
    override val id = LanguageSupport.ID_JAVA

    // Lazy so headless tests can read other fields without forcing the Language
    // registry lookup (Java plugin isn't on the headless test classpath).
    override val language: Language? by lazy {
        Language.findLanguageByID("JAVA")
    }
    override val displayName = "Java"
    override val fileExtensions = setOf("java")

    override fun findRelatedTypes(
        psiFile: PsiFile,
        project: Project,
        scope: GlobalSearchScope,
    ): String? {
        if (psiFile !is PsiJavaFile) return null
        if (psiFile.importList == null) return "No imports found."
        val entries = enumerateRelatedTypes(psiFile, project, scope)
        if (entries.isEmpty()) return "No project-scope related types found in imports."
        val sb = StringBuilder()
        for (entry in entries) {
            sb.appendLine(entry.text)
            sb.appendLine()
        }
        return sb.toString()
    }

    override fun enumerateRelatedTypes(
        psiFile: PsiFile,
        project: Project,
        scope: GlobalSearchScope,
    ): List<LanguageSupport.RelatedType> {
        if (psiFile !is PsiJavaFile) return emptyList()
        val importList = psiFile.importList ?: return emptyList()
        val out = mutableListOf<LanguageSupport.RelatedType>()
        for (importStmt in importList.importStatements.take(MAX_IMPORTS)) {
            val qualifiedName = importStmt.qualifiedName ?: continue
            val resolved = JavaPsiFacade.getInstance(project).findClass(qualifiedName, scope) ?: continue
            val kind = if (resolved.isInterface) "interface" else "class"
            val text = buildString {
                appendLine("--- $kind ${resolved.name} ---")
                for (m in resolved.methods.take(10)) {
                    appendLine("  ${PsiUtils.formatMethodSignature(m)}")
                }
                for (f in resolved.fields.take(5)) {
                    appendLine("  ${f.type.presentableText} ${f.name}")
                }
            }.trimEnd()
            out.add(LanguageSupport.RelatedType(name = resolved.name ?: qualifiedName, text = text))
        }
        return out
    }

    private const val MAX_IMPORTS = 15
}
