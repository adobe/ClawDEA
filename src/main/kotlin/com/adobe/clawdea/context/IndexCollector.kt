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

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Collects cross-file context from IntelliJ's indices:
 * callers, implementations, usages, supertypes, and related types.
 *
 * Profile-based query restriction:
 * - CHAT / ACTION: all five queries
 * - COMPLETION: related types + supertype signatures only
 */
class IndexCollector {

    private val log = Logger.getInstance(IndexCollector::class.java)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClawDEA-index-query").apply { isDaemon = true }
    }

    fun collect(
        editor: Editor,
        psiFile: PsiFile,
        project: Project,
        profile: ContextProfile,
    ): List<ContextItem> {
        if (DumbService.isDumb(project)) {
            log.info("Skipping IndexCollector — indexing in progress")
            return emptyList()
        }

        val items = mutableListOf<ContextItem>()

        val (containingClass, containingMethod) = runReadAction {
            val offset = editor.caretModel.offset
            val element = psiFile.findElementAt(offset)
            val cls = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
            val mtd = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            Pair(cls, mtd)
        }

        val scope = GlobalSearchScope.projectScope(project)

        // All profiles get related types + supertypes
        if (containingClass != null) {
            items.addAll(queryRelatedTypes(psiFile, project))
            items.addAll(querySupertypeSignatures(containingClass))
        }

        // CHAT and ACTION also get callers, implementations, usages
        if (profile != ContextProfile.COMPLETION) {
            if (containingMethod != null) {
                items.addAll(queryCallers(containingMethod, scope))
            }
            if (containingClass != null) {
                items.addAll(queryImplementations(containingClass, scope))
                items.addAll(queryUsages(containingClass, scope))
            }
        }

        return items
    }

    private fun queryCallers(method: PsiMethod, scope: GlobalSearchScope): List<ContextItem> {
        return runWithTimeout("callers") {
            runReadAction {
                val refs = ReferencesSearch.search(method, scope).findAll()
                val items = mutableListOf<ContextItem>()
                for (ref in refs.take(5)) {
                    val element = ref.element
                    val file = element.containingFile ?: continue
                    val lineNumber = getLineNumber(file, element.textOffset)
                    val context = getSurroundingLines(file, element.textOffset, 3)
                    items.add(ContextItem(
                        label = "Caller of ${method.name} — ${file.name}:$lineNumber",
                        content = context,
                        score = 0.85,
                        source = "index"
                    ))
                }
                items
            }
        }
    }

    private fun queryImplementations(psiClass: PsiClass, scope: GlobalSearchScope): List<ContextItem> {
        return runWithTimeout("implementations") {
            runReadAction {
                val inheritors = ClassInheritorsSearch.search(psiClass, scope, false).findAll()
                val items = mutableListOf<ContextItem>()
                for (impl in inheritors.take(5)) {
                    val text = buildString {
                        appendLine("class ${impl.name}")
                        for (method in impl.methods.take(10)) {
                            appendLine("  ${formatMethodSignature(method)}")
                        }
                    }
                    items.add(ContextItem(
                        label = "Implementation: ${impl.name}",
                        content = text,
                        score = 0.8,
                        source = "index"
                    ))
                }
                items
            }
        }
    }

    private fun queryUsages(psiClass: PsiClass, scope: GlobalSearchScope): List<ContextItem> {
        return runWithTimeout("usages") {
            runReadAction {
                val refs = ReferencesSearch.search(psiClass as PsiElement, scope).findAll()
                val items = mutableListOf<ContextItem>()
                for (ref in refs.take(8)) {
                    val element = ref.element
                    val file = element.containingFile ?: continue
                    val lineNumber = getLineNumber(file, element.textOffset)
                    val context = getSurroundingLines(file, element.textOffset, 2)
                    items.add(ContextItem(
                        label = "Usage of ${psiClass.name} — ${file.name}:$lineNumber",
                        content = context,
                        score = 0.75,
                        source = "index"
                    ))
                }
                items
            }
        }
    }

    private fun querySupertypeSignatures(psiClass: PsiClass): List<ContextItem> {
        return runWithTimeout("supertypes") {
            runReadAction {
                val items = mutableListOf<ContextItem>()
                for (superType in psiClass.supers) {
                    if (superType.qualifiedName in setOf("java.lang.Object", "kotlin.Any")) continue
                    val text = buildString {
                        appendLine("${superType.name}:")
                        for (method in superType.methods) {
                            appendLine("  ${formatMethodSignature(method)}")
                        }
                    }
                    if (text.isNotBlank()) {
                        items.add(ContextItem(
                            label = "Supertype: ${superType.name}",
                            content = text,
                            score = 0.7,
                            source = "index"
                        ))
                    }
                }
                items
            }
        }
    }

    private fun queryRelatedTypes(psiFile: PsiFile, project: Project): List<ContextItem> {
        return runWithTimeout("related-types") {
            runReadAction {
                val items = mutableListOf<ContextItem>()
                // PSI-type-specific branch. Extending to Scala/etc. will require either
                // adding a per-language collector hook to LanguageSupport (sub-project #3)
                // or expanding this when-branch.
                if (psiFile !is PsiJavaFile) return@runReadAction items

                val importList = psiFile.importList ?: return@runReadAction items
                val scope = GlobalSearchScope.projectScope(project)

                for (importStmt in importList.importStatements.take(15)) {
                    val qualifiedName = importStmt.qualifiedName ?: continue
                    val resolved = JavaPsiFacade.getInstance(project)
                        .findClass(qualifiedName, scope) ?: continue

                    val text = buildString {
                        val modifiers = resolved.modifierList?.text?.trim() ?: ""
                        if (modifiers.isNotBlank()) append("$modifiers ")
                        append(if (resolved.isInterface) "interface " else "class ")
                        appendLine(resolved.name)
                        for (method in resolved.methods.take(10)) {
                            appendLine("  ${formatMethodSignature(method)}")
                        }
                        for (field in resolved.fields.take(5)) {
                            appendLine("  ${field.type.presentableText} ${field.name}")
                        }
                    }

                    items.add(ContextItem(
                        label = "Related type: ${resolved.name}",
                        content = text,
                        score = 0.5,
                        source = "index"
                    ))
                }
                items
            }
        }
    }

    /**
     * Runs a query with a 500ms timeout. Returns empty list on timeout or error.
     */
    private fun runWithTimeout(queryName: String, block: () -> List<ContextItem>): List<ContextItem> {
        return try {
            val future = executor.submit(Callable {
                val indicator = EmptyProgressIndicator()
                ProgressManager.getInstance().runProcess(block, indicator)
            })
            future.get(500, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            log.info("IndexCollector query '$queryName' timed out after 500ms")
            emptyList()
        } catch (e: java.util.concurrent.CancellationException) {
            log.debug("IndexCollector query '$queryName' was cancelled")
            emptyList()
        } catch (e: com.intellij.openapi.project.IndexNotReadyException) {
            log.info("IndexCollector query '$queryName' skipped — index not ready")
            emptyList()
        } catch (e: Exception) {
            log.warn("IndexCollector query '$queryName' failed: ${e.message}")
            emptyList()
        }
    }

    private fun formatMethodSignature(method: PsiMethod): String {
        val returnType = method.returnType?.presentableText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") {
            "${it.type.presentableText} ${it.name}"
        }
        return "$returnType ${method.name}($params)"
    }

    private fun getLineNumber(file: PsiFile, offset: Int): Int {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
            ?: return 0
        return document.getLineNumber(offset) + 1
    }

    private fun getSurroundingLines(file: PsiFile, offset: Int, surroundingLines: Int): String {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
            ?: return ""
        val lineNumber = document.getLineNumber(offset)
        val startLine = (lineNumber - surroundingLines).coerceAtLeast(0)
        val endLine = (lineNumber + surroundingLines).coerceAtMost(document.lineCount - 1)
        val startOffset = document.getLineStartOffset(startLine)
        val endOffset = document.getLineEndOffset(endLine)
        return document.text.substring(startOffset, endOffset)
    }
}
