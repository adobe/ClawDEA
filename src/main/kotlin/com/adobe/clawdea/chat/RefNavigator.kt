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

import com.adobe.clawdea.language.LanguageSupportRegistry
import com.adobe.clawdea.util.runReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * Resolves a `{[ref:...]}` chat link to a location and navigates the IDE to it: file-path first,
 * then PSI (FQCN / Class.method / bare class), then a filename fallback, then Search Everywhere.
 * Pure index/navigation logic extracted out of ChatPanel (Tier 5.1) — it depends only on [project],
 * not on any chat UI state.
 */
internal class RefNavigator(private val project: Project) {

    private fun navPriority(filePath: String): Int {
        val rel = filePath.removePrefix(project.basePath ?: "")
        return when {
            rel.startsWith("/src/") -> 0
            rel.startsWith("/.claude/worktrees/") || rel.startsWith("/bin/") -> 2
            else -> 1
        }
    }

    fun navigateToRef(ref: String) {
        val basePath = project.basePath ?: ""

        val parsed = RefParser.parse(ref) ?: return
        val path = parsed.path
        val explicitLine = parsed.startLine
        val line = explicitLine ?: 0
        val col = parsed.column

        // Try file path resolution
        val vf = when {
            path.startsWith("/") -> LocalFileSystem.getInstance().findFileByPath(path)
            path.contains("/") -> LocalFileSystem.getInstance().findFileByPath("$basePath/$path")
            path.contains(".") && !path.contains("(") -> {
                val filename = path.substringAfterLast("/")
                runReadAction {
                    FilenameIndex.getVirtualFilesByName(filename, GlobalSearchScope.projectScope(project))
                        .sortedBy { navPriority(it.path) }
                        .firstOrNull()
                }
            }
            else -> null
        }
        if (vf != null) {
            ApplicationManager.getApplication().invokeLater {
                OpenFileDescriptor(project, vf, line, col).navigate(true)
                if (parsed.isRange) selectLineRange(vf, parsed.startLine!!, parsed.endLine!!)
            }
            return
        }

        // Try PSI resolution (FQCN, Class.method, or bare class name)
        var resolved = false
        if (com.intellij.openapi.project.DumbService.getInstance(project).isDumb) {
            // Indexes not ready — fall through to Search Everywhere
        } else runReadAction {
            val cache = PsiShortNamesCache.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)

            if (path.contains(".")) {
                val segments = path.removeSuffix("()").split(".")
                val classIdx = segments.indexOfLast { it.firstOrNull()?.isUpperCase() == true }
                if (classIdx >= 0) {
                    val className = segments[classIdx]
                    val fqn = segments.subList(0, classIdx + 1).joinToString(".")
                    val methodName = if (classIdx + 1 < segments.size) segments[classIdx + 1] else null
                    val classes = cache.getClassesByName(className, scope)
                        .sortedBy { navPriority(it.containingFile?.virtualFile?.path ?: "") }
                    val targetClass = classes.firstOrNull { it.qualifiedName == fqn }
                        ?: classes.firstOrNull()
                    if (targetClass != null) {
                        val target = methodName?.let { m ->
                            targetClass.findMethodsByName(m, false).firstOrNull()
                        } ?: targetClass
                        val file = target.containingFile?.virtualFile
                        if (file != null) {
                            resolved = true
                            ApplicationManager.getApplication().invokeLater {
                                val descriptor = if (explicitLine != null) {
                                    OpenFileDescriptor(project, file, explicitLine, col)
                                } else {
                                    OpenFileDescriptor(project, file, target.textOffset)
                                }
                                descriptor.navigate(true)
                                if (parsed.isRange) selectLineRange(file, parsed.startLine!!, parsed.endLine!!)
                            }
                            return@runReadAction
                        }
                    }
                }
            }

            val classes = cache.getClassesByName(path, scope)
            val cls = classes
                .sortedBy { navPriority(it.containingFile?.virtualFile?.path ?: "") }
                .firstOrNull() ?: return@runReadAction
            val file = cls.containingFile?.virtualFile ?: return@runReadAction
            resolved = true
            ApplicationManager.getApplication().invokeLater {
                val descriptor = if (explicitLine != null) {
                    OpenFileDescriptor(project, file, explicitLine, col)
                } else {
                    OpenFileDescriptor(project, file, cls.textOffset)
                }
                descriptor.navigate(true)
                if (parsed.isRange) selectLineRange(file, parsed.startLine!!, parsed.endLine!!)
            }
        }

        if (!resolved) {
            // Try filename-based fallback: derive ClassName.kt / ClassName.java from the path
            val classNameForFile = path.substringAfterLast(".").removeSuffix("()")
                .takeIf { it.firstOrNull()?.isUpperCase() == true }
                ?: path.takeIf { it.firstOrNull()?.isUpperCase() == true && !it.contains(".") }
            if (classNameForFile != null) {
                val scope = GlobalSearchScope.projectScope(project)
                val candidateExtensions = LanguageSupportRegistry.all()
                    .flatMap { it.fileExtensions }
                    .ifEmpty { listOf("kt", "java") }
                val fallbackFile = runReadAction {
                    candidateExtensions.asSequence()
                        .map { ext -> "$classNameForFile.$ext" }
                        .flatMap { name -> FilenameIndex.getVirtualFilesByName(name, scope).asSequence() }
                        .sortedBy { navPriority(it.path) }
                        .firstOrNull()
                }
                if (fallbackFile != null) {
                    resolved = true
                    ApplicationManager.getApplication().invokeLater {
                        OpenFileDescriptor(project, fallbackFile, line, col).navigate(true)
                        if (parsed.isRange) selectLineRange(fallbackFile, parsed.startLine!!, parsed.endLine!!)
                    }
                }
            }
        }

        if (!resolved) {
            val searchText = path.substringAfterLast(".").removeSuffix("()")
                .ifBlank { path }
            ApplicationManager.getApplication().invokeLater {
                val action = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("SearchEverywhere") ?: return@invokeLater
                val manager = com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
                    .getInstance(project)
                if (manager.isShown) return@invokeLater
                val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                        .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                        .build()
                val event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                    dataContext,
                    action.templatePresentation.clone(),
                    com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN,
                    com.intellij.openapi.actionSystem.ActionUiKind.NONE,
                    null,
                )
                manager.show(com.intellij.ide.actions.searcheverywhere.SearchEverywhereManagerImpl.ALL_CONTRIBUTORS_GROUP_ID, searchText, event)
            }
        }
    }

    /**
     * Select lines `[startLine..endLine]` (0-based, inclusive) in whichever
     * editor was just opened for `file`. The range is clamped to the
     * document; the caret is parked at the start so the selection unfolds
     * downward in the IDE.
     */
    private fun selectLineRange(file: VirtualFile, startLine: Int, endLine: Int) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val docFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
        if (docFile != file) return
        val doc = editor.document
        if (doc.lineCount == 0) return
        val s = startLine.coerceIn(0, doc.lineCount - 1)
        val e = endLine.coerceIn(s, doc.lineCount - 1)
        val startOffset = doc.getLineStartOffset(s)
        val endOffset = doc.getLineEndOffset(e)
        editor.caretModel.moveToOffset(startOffset)
        editor.selectionModel.setSelection(startOffset, endOffset)
    }
}
