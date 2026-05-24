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
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference
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
            log.debug("ScalaPluginPsiBridge: psiFile is not ScalaFile (class=${psiFile.javaClass.name})")
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
        log.debug("ScalaPluginPsiBridge: ${imports.size} import statement(s) in ${psiFile.name}")
        if (imports.isEmpty()) return "No imports found."

        val sb = StringBuilder()
        for (stmt in imports.take(15)) {
            for (expr in scalaSeqToList<ScImportExpr>(stmt.importExprs())) {
                renderExpr(expr, sb)
            }
        }
        return if (sb.isEmpty()) "No project-scope related types found in imports." else sb.toString()
    }

    override fun findImplicitDefinitions(psiFile: PsiFile): String? {
        // Stub — real implementation lands in the next commit. Returning the
        // empty-state sentinel keeps the call path live and lets the MCP tool
        // be wired up in parallel without an intermediate-state failure mode.
        if (psiFile !is ScalaFile) return null
        return "No implicit definitions found."
    }

    /**
     * Renders each class-shaped import target referenced by [expr]. Uses the Scala
     * plugin's own [ScStableCodeReference.resolve] to traverse qualifier chains and map
     * imported names (including aliases) to the underlying [PsiElement]. This is more
     * robust than reconstructing fully-qualified names from `qualName()` — for selector
     * references inside braces, `qualName()` only returns the leaf identifier (e.g.
     * `"ArrayList"`), and combining it with `expr.qualifier()` requires per-form casing
     * that the plugin's resolver already handles.
     */
    private fun renderExpr(expr: ScImportExpr, sb: StringBuilder) {
        val selectors = scalaSeqToList<ScImportSelector>(expr.selectors())

        if (selectors.isEmpty()) {
            // Simple form: `import foo.Bar` — the expr's reference IS the full path.
            val refOpt = expr.reference()
            if (refOpt.isDefined) {
                tryRenderRef(refOpt.get(), sb)
            } else {
                log.debug("  expr has no reference and no selectors")
            }
            return
        }

        for (selector in selectors) {
            if (selector.isWildcardSelector) {
                log.debug("  selector is wildcard (skipped)")
                continue
            }
            if (selector.isGivenSelector) {
                log.debug("  selector is given (skipped)")
                continue
            }
            val refOpt = selector.reference()
            if (refOpt.isEmpty) {
                log.debug("  selector has no reference")
                continue
            }
            tryRenderRef(refOpt.get(), sb)
        }
    }

    private fun tryRenderRef(ref: ScStableCodeReference, sb: StringBuilder) {
        val qualName = ref.qualName()
        // ScReference is a PsiPolyVariantReference via ScPolyResolvable. The default
        // PsiPolyVariantReference.resolve() returns null when multiResolve returns
        // multiple results — which is exactly the case for Scala class+companion-object
        // pairs (e.g. `models.User` binds both the case class User AND its companion
        // object User to the same name). Use multiResolve and pick the first PsiClass.
        val results = try {
            ref.multiResolve(false)
        } catch (e: Throwable) {
            log.warn("  '$qualName' multiResolve threw ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val resolvedClass = results.asSequence()
            .mapNotNull { it.element as? PsiClass }
            .firstOrNull()
        if (resolvedClass != null) {
            log.debug("  '$qualName' resolved to PsiClass ${resolvedClass.qualifiedName ?: resolvedClass.name} (multiResolve returned ${results.size} result(s))")
            renderClass(resolvedClass, sb)
        } else if (results.isEmpty()) {
            log.debug("  '$qualName' multiResolve returned no results")
        } else {
            log.debug("  '$qualName' multiResolve returned ${results.size} result(s), none a PsiClass")
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
