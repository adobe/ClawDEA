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

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

enum class MentionType {
    FILE,
}

data class MentionItem(
    val displayName: String,
    val insertValue: String,
    val type: MentionType,
    val context: String? = null,
    val virtualFile: VirtualFile? = null,
)

class MentionCompletionProvider(private val project: Project) {

    fun extractMentionPrefix(text: String, caretOffset: Int): String? {
        val safeOffset = caretOffset.coerceIn(0, text.length)
        val before = text.substring(0, safeOffset)
        val atIndex = before.lastIndexOf('@')
        if (atIndex < 0) return null
        val prefix = before.substring(atIndex + 1)
        if (prefix.contains(' ')) return null
        return prefix
    }

    fun getInitialItems(maxResults: Int = 15): List<MentionItem> {
        val basePath = project.basePath ?: return emptyList()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<MentionItem>()

        val openFiles = FileEditorManager.getInstance(project).openFiles
        for (vf in openFiles) {
            val rel = relativePath(vf, basePath)
            if (seen.add(rel)) {
                result.add(MentionItem(vf.name, rel, MentionType.FILE, virtualFile = vf))
            }
            if (result.size >= maxResults) return result
        }

        val recentFiles = getRecentlyModifiedFiles(maxResults * 2)
        for (vf in recentFiles) {
            val rel = relativePath(vf, basePath)
            if (seen.add(rel)) {
                result.add(MentionItem(vf.name, rel, MentionType.FILE, virtualFile = vf))
            }
            if (result.size >= maxResults) return result
        }

        return result
    }

    fun searchFiles(prefix: String, maxResults: Int = 15): List<MentionItem> {
        if (prefix.isBlank()) return getInitialItems(maxResults)
        val basePath = project.basePath ?: return emptyList()
        val lower = prefix.lowercase()
        val scope = GlobalSearchScope.projectScope(project)
        val result = mutableListOf<MentionItem>()
        val seen = mutableSetOf<String>()

        com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
            val allNames = FilenameIndex.getAllFilenames(project)

            for (name in allNames) {
                if (!name.lowercase().contains(lower)) continue
                for (vf in FilenameIndex.getVirtualFilesByName(name, scope)) {
                    val rel = relativePath(vf, basePath)
                    if (seen.add(rel)) {
                        result.add(MentionItem(vf.name, rel, MentionType.FILE, virtualFile = vf))
                    }
                    if (result.size >= maxResults) return@runReadAction
                }
            }

            if (result.size < maxResults) {
                for (name in allNames) {
                    for (vf in FilenameIndex.getVirtualFilesByName(name, scope)) {
                        val rel = relativePath(vf, basePath)
                        if (rel.lowercase().contains(lower) && seen.add(rel)) {
                            result.add(MentionItem(vf.name, rel, MentionType.FILE, virtualFile = vf))
                        }
                        if (result.size >= maxResults) return@runReadAction
                    }
                }
            }
        }

        return result
    }

    fun searchSymbols(prefix: String, maxResults: Int = 15): List<MentionItem> {
        if (prefix.isBlank()) return emptyList()
        val basePath = project.basePath ?: return emptyList()
        val lower = prefix.lowercase()
        val scope = GlobalSearchScope.projectScope(project)
        val result = mutableListOf<MentionItem>()
        val seen = mutableSetOf<String>()

        com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
            val cache = PsiShortNamesCache.getInstance(project)

            for (className in cache.allClassNames) {
                if (!className.lowercase().contains(lower)) continue
                val classes = cache.getClassesByName(className, scope)
                for (psiClass in classes) {
                    val vf = psiClass.containingFile?.virtualFile ?: continue
                    val rel = relativePath(vf, basePath)
                    val key = "$rel#$className"
                    if (seen.add(key)) {
                        result.add(MentionItem(vf.name, rel, MentionType.FILE, context = className, virtualFile = vf))
                    }
                    if (result.size >= maxResults) return@runReadAction
                }
            }

            for (methodName in cache.allMethodNames) {
                if (!methodName.lowercase().contains(lower)) continue
                val methods = cache.getMethodsByName(methodName, scope)
                for (method in methods) {
                    val vf = method.containingFile?.virtualFile ?: continue
                    val rel = relativePath(vf, basePath)
                    val parentClass = method.containingClass?.name ?: ""
                    val ctx = "$parentClass.$methodName"
                    val key = "$rel#$ctx"
                    if (seen.add(key)) {
                        result.add(MentionItem(vf.name, rel, MentionType.FILE, context = ctx, virtualFile = vf))
                    }
                    if (result.size >= maxResults) return@runReadAction
                }
            }
        }

        return result
    }

    fun buildReplacementText(item: MentionItem): String {
        return "@`${item.insertValue}` "
    }

    private fun getRecentlyModifiedFiles(max: Int): List<VirtualFile> {
        val basePath = project.basePath ?: return emptyList()
        return try {
            val process = ProcessBuilder("git", "log", "--diff-filter=M", "--name-only", "--pretty=format:", "-50")
                .directory(java.io.File(basePath))
                .redirectErrorStream(true)
                .start()
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            lines.asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .mapNotNull { lfs.findFileByPath("$basePath/$it") }
                .filter { it.isValid && !it.isDirectory }
                .take(max)
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        fun relativePath(vf: VirtualFile, basePath: String): String {
            val path = vf.path
            if (path.startsWith(basePath)) {
                return path.removePrefix(basePath).removePrefix("/")
            }
            return path
        }
    }
}
