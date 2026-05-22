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
    // Lazy so headless unit tests can read other fields without triggering the
    // Language registry lookup (Java plugin isn't on the headless test classpath).
    override val language: Language by lazy {
        Language.findLanguageByID("JAVA")
            ?: error("Java language not registered — Java plugin missing?")
    }
    override val displayName = "Java"
    override val fileExtensions = setOf("java")

    /**
     * Moved verbatim from McpIndexTools.findRelatedTypes. Caller is responsible
     * for runReadAction + DumbService guards.
     *
     * Returns:
     *  - null when the file is not a Java file (caller decides how to render)
     *  - non-null string otherwise (possibly the "No imports found." sentinel)
     */
    override fun findRelatedTypes(
        psiFile: PsiFile,
        project: Project,
        scope: GlobalSearchScope,
    ): String? {
        if (psiFile !is PsiJavaFile) return null
        val importList = psiFile.importList ?: return "No imports found."
        val sb = StringBuilder()
        for (importStmt in importList.importStatements.take(15)) {
            val qualifiedName = importStmt.qualifiedName ?: continue
            val resolved = JavaPsiFacade.getInstance(project).findClass(qualifiedName, scope) ?: continue
            val kind = if (resolved.isInterface) "interface" else "class"
            sb.appendLine("--- $kind ${resolved.name} ---")
            for (m in resolved.methods.take(10)) {
                sb.appendLine("  ${PsiUtils.formatMethodSignature(m)}")
            }
            for (f in resolved.fields.take(5)) {
                sb.appendLine("  ${f.type.presentableText} ${f.name}")
            }
            sb.appendLine()
        }
        return if (sb.isEmpty()) "No project-scope related types found in imports." else sb.toString()
    }
}
