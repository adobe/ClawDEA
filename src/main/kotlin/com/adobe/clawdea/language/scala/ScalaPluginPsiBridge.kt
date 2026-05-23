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

import com.adobe.clawdea.mcp.PsiUtils
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportExpr
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportSelector
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportStmt

/**
 * [ScalaPsiBridge] implementation that walks the IntelliJ Scala plugin's PSI to render
 * class/trait/object signatures from a Scala file's imports. Only loaded when the Scala
 * plugin is installed (registered in `clawdea-scala.xml`).
 *
 * Output matches Java's findRelatedTypes shape — caller and agent treat them uniformly.
 * Wildcard imports (`import foo._`/`import foo.*`), given imports (`import foo.given`),
 * and method imports are silently skipped — this PR handles classes/traits/objects only.
 */
class ScalaPluginPsiBridge : ScalaPsiBridge {

    private val log = Logger.getInstance(ScalaPluginPsiBridge::class.java)

    override fun findRelatedTypes(
        psiFile: PsiFile,
        project: Project,
        scope: GlobalSearchScope,
    ): String? {
        if (psiFile !is ScalaFile) {
            log.info("ScalaPluginPsiBridge: psiFile is not ScalaFile (class=${psiFile.javaClass.name})")
            return null
        }
        // Walk the full PSI tree rather than calling ScalaFile.getImportStatements().
        // The latter only returns direct file children; in Scala files with a top-level
        // package declaration (`package foo`), imports are wrapped in a ScPackaging
        // element and therefore missed. PsiTreeUtil.findChildrenOfType descends the
        // whole tree.
        val imports = try {
            PsiTreeUtil.findChildrenOfType(psiFile, ScImportStmt::class.java).toList()
        } catch (e: Throwable) {
            log.warn("ScalaPluginPsiBridge: failed to collect import statements: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
        log.info("ScalaPluginPsiBridge: ${imports.size} import statement(s) in ${psiFile.name}")
        if (imports.isEmpty()) return "No imports found."

        val sb = StringBuilder()
        for (stmt in imports.take(15)) {
            for (expr in scalaSeqToList<ScImportExpr>(stmt.importExprs())) {
                renderExpr(expr, project, scope, sb)
            }
        }
        return if (sb.isEmpty()) "No project-scope related types found in imports." else sb.toString()
    }

    private fun renderExpr(
        expr: ScImportExpr,
        project: Project,
        scope: GlobalSearchScope,
        sb: StringBuilder,
    ) {
        val facade = JavaPsiFacade.getInstance(project)
        val selectors = scalaSeqToList<ScImportSelector>(expr.selectors())

        if (selectors.isEmpty()) {
            // Simple form: `import foo.Bar` — qualifier itself is the target.
            val qualifierOpt = expr.qualifier()
            if (qualifierOpt.isDefined) {
                val qualName = qualifierOpt.get().qualName()
                resolveAndRender(facade, qualName, project, scope, sb)
            } else {
                log.info("  expr has no qualifier and no selectors")
            }
            return
        }

        for (selector in selectors) {
            if (selector.isWildcardSelector) {
                log.info("  selector is wildcard (skipped)")
                continue
            }
            if (selector.isGivenSelector) {
                log.info("  selector is given (skipped)")
                continue
            }
            val refOpt = selector.reference()
            if (refOpt.isEmpty) {
                log.info("  selector has no reference")
                continue
            }
            val qualName = refOpt.get().qualName()
            resolveAndRender(facade, qualName, project, scope, sb)
        }
    }

    private fun resolveAndRender(
        facade: JavaPsiFacade,
        qualName: String,
        project: Project,
        scope: GlobalSearchScope,
        sb: StringBuilder,
    ) {
        val cls = facade.findClass(qualName, scope)
        if (cls != null) {
            log.info("  '$qualName' resolved via projectScope -> ${cls.qualifiedName}")
            renderClass(cls, sb)
            return
        }
        val clsAll = facade.findClass(qualName, com.intellij.psi.search.GlobalSearchScope.allScope(project))
        if (clsAll != null) {
            log.info("  '$qualName' NOT in projectScope, found in allScope (skipped — not a project type)")
        } else {
            log.info("  '$qualName' did not resolve via JavaPsiFacade.findClass in any scope")
        }
    }

    private fun renderClass(cls: PsiClass, sb: StringBuilder) {
        val kind = if (cls.isInterface) "trait" else "class"
        sb.appendLine("--- $kind ${cls.name} ---")
        for (m in cls.methods.take(10)) {
            sb.appendLine("  ${PsiUtils.formatMethodSignature(m)}")
        }
        for (f in cls.fields.take(5)) {
            sb.appendLine("  ${f.type.presentableText} ${f.name}")
        }
        sb.appendLine()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> scalaSeqToList(seq: scala.collection.immutable.Seq<*>): List<T> {
        val out = ArrayList<T>()
        val it = seq.iterator()
        while (it.hasNext()) {
            out.add(it.next() as T)
        }
        return out
    }
}
