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
package com.adobe.clawdea.chat

import com.adobe.clawdea.util.runReadAction

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * Handles /callers, /implementations, /usages, /supertypes chat commands.
 * Queries IntelliJ indices and returns formatted HTML for the chat panel.
 */
class IndexQueryHandler(private val project: Project) {

    fun handleCommand(command: String, editor: Editor?, offset: Int = -1): String {
        if (editor == null) {
            return renderInfo("No active editor.")
        }

        if (DumbService.isDumb(project)) {
            return renderInfo("Indexing in progress, try again shortly.")
        }

        val caretOffset = if (offset >= 0) offset else editor.caretModel.offset

        return when (command) {
            "/callers" -> handleCallers(editor, caretOffset)
            "/implementations" -> handleImplementations(editor, caretOffset)
            "/usages" -> handleUsages(editor, caretOffset)
            "/supertypes" -> handleSupertypes(editor, caretOffset)
            else -> renderInfo("Unknown index command: $command")
        }
    }

    private fun handleCallers(editor: Editor, caretOffset: Int): String {
        val target = runReadAction {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@runReadAction null
            val element = psiFile.findElementAt(caretOffset)
            // Try to resolve the reference at the caret (e.g. a method call)
            val resolved = element?.parent?.reference?.resolve()
            if (resolved is PsiNamedElement) return@runReadAction resolved
            // Fall back to the enclosing function/method definition.
            // Check PsiMethod (Java) first, then walk up to any PsiNamedElement
            // whose class name contains "Function" or "Method" (covers Kotlin's
            // KtNamedFunction without a compile-time dependency on the Kotlin plugin).
            PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                ?: generateSequence(element) { it.parent }
                    .filterIsInstance<PsiNamedElement>()
                    .firstOrNull { it.javaClass.simpleName.contains("Function") }
        } ?: return renderInfo("No method found at caret.")

        val results = runReadAction {
            val scope = GlobalSearchScope.projectScope(project)
            val refs = ReferencesSearch.search(target, scope).findAll()
            refs.take(20).mapNotNull { ref ->
                val element = ref.element
                val file = element.containingFile ?: return@mapNotNull null
                val lineNumber = getLineNumber(file, element.textOffset)
                val context = getSurroundingLines(file, element.textOffset, 2)
                ResultEntry("${file.name}:$lineNumber", context)
            }
        }

        val methodName = runReadAction { target.name ?: "unknown" }
        return renderResults("Callers of $methodName", results)
    }

    private fun handleImplementations(editor: Editor, caretOffset: Int): String {
        val psiClass = runReadAction {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@runReadAction null
            val element = psiFile.findElementAt(caretOffset)
            PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        } ?: return renderInfo("No class or interface found at caret.")

        val results = runReadAction {
            val scope = GlobalSearchScope.projectScope(project)
            val inheritors = ClassInheritorsSearch.search(psiClass, scope, false).findAll()
            inheritors.take(20).map { impl ->
                val methods = impl.methods.take(10).joinToString("\n") { m ->
                    "  ${formatMethodSignature(m)}"
                }
                ResultEntry(impl.name ?: "Anonymous", "class ${impl.name}\n$methods")
            }
        }

        val className = runReadAction { psiClass.name ?: "Unknown" }
        return renderResults("Implementations of $className", results)
    }

    private fun handleUsages(editor: Editor, caretOffset: Int): String {
        val (element, name) = runReadAction {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@runReadAction Pair(null, "")
            val el = psiFile.findElementAt(caretOffset)
            val ref = el?.reference?.resolve() ?: PsiTreeUtil.getParentOfType(el, PsiNamedElement::class.java)
            val n = when (ref) {
                is PsiNamedElement -> ref.name ?: "symbol"
                else -> "symbol"
            }
            Pair(ref, n)
        }

        if (element == null) {
            return renderInfo("No symbol found at caret.")
        }

        val results = runReadAction {
            val scope = GlobalSearchScope.projectScope(project)
            val refs = ReferencesSearch.search(element, scope).findAll()
            refs.take(20).mapNotNull { ref ->
                val el = ref.element
                val file = el.containingFile ?: return@mapNotNull null
                val lineNumber = getLineNumber(file, el.textOffset)
                val context = getSurroundingLines(file, el.textOffset, 2)
                ResultEntry("${file.name}:$lineNumber", context)
            }
        }

        return renderResults("Usages of $name", results)
    }

    private fun handleSupertypes(editor: Editor, caretOffset: Int): String {
        val psiClass = runReadAction {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@runReadAction null
            val element = psiFile.findElementAt(caretOffset)
            PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        } ?: return renderInfo("No class found at caret.")

        val results = runReadAction {
            psiClass.supers
                .filter { it.qualifiedName != "java.lang.Object" }
                .map { superType ->
                    val methods = superType.methods.joinToString("\n") { m ->
                        "  ${formatMethodSignature(m)}"
                    }
                    val kind = if (superType.isInterface) "interface" else "class"
                    ResultEntry(
                        "$kind ${superType.name}",
                        "$kind ${superType.name}\n$methods"
                    )
                }
        }

        val className = runReadAction { psiClass.name ?: "Unknown" }
        return renderResults("Type hierarchy for $className", results)
    }

    private fun renderResults(title: String, results: List<ResultEntry>): String {
        if (results.isEmpty()) {
            return renderInfo("$title: no results found.")
        }

        val rows = results.joinToString("") { entry ->
            val escaped = escapeHtml(entry.content)
            """
            <div style="margin-bottom: 8px; padding: 8px; background: #1e1e2e; border-radius: 6px; border-left: 3px solid #89b4fa;">
                <div style="color: #89b4fa; font-size: 12px; margin-bottom: 4px; font-weight: 600;">${escapeHtml(entry.label)}</div>
                <pre style="margin: 0; font-size: 12px; color: #cdd6f4; white-space: pre-wrap; font-family: 'JetBrains Mono', monospace;">$escaped</pre>
            </div>
            """.trimIndent()
        }

        return """
            <div class="message">
                <div class="message-label assistant-label">Index Query</div>
                <div class="assistant-bubble">
                    <div style="font-weight: 600; margin-bottom: 8px; color: #cdd6f4;">$title (${results.size} results)</div>
                    $rows
                </div>
            </div>
        """.trimIndent()
    }

    private fun renderInfo(text: String): String {
        return """<div class="info-block">${escapeHtml(text)}</div>"""
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
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

    private data class ResultEntry(val label: String, val content: String)
}
