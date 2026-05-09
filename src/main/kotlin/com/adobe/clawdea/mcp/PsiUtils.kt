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
package com.adobe.clawdea.mcp

import com.adobe.clawdea.util.runReadAction

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * Shared PSI utilities for MCP tool handlers.
 */
object PsiUtils {

    /**
     * Resolve a file path (absolute or project-relative) to a PsiFile.
     */
    fun resolvePsiFile(project: Project, filePath: String): PsiFile? {
        return runReadAction {
            val basePath = project.basePath ?: return@runReadAction null
            val absolutePath = if (filePath.startsWith("/")) filePath else "$basePath/$filePath"
            val vf = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return@runReadAction null
            PsiManager.getInstance(project).findFile(vf)
        }
    }

    /**
     * Find the PsiElement at a given 1-based line number.
     */
    fun elementAtLine(psiFile: PsiFile, line: Int): PsiElement? {
        return runReadAction {
            val document = PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile)
                ?: return@runReadAction null
            val lineIndex = (line - 1).coerceIn(0, document.lineCount - 1)
            val offset = document.getLineStartOffset(lineIndex)
            // Walk forward to first non-whitespace element
            val lineEndOffset = document.getLineEndOffset(lineIndex)
            var element: PsiElement? = null
            var pos = offset
            while (pos <= lineEndOffset) {
                element = psiFile.findElementAt(pos)
                if (element != null && element !is PsiWhiteSpace) break
                pos++
            }
            element
        }
    }

    fun findClassAtLine(psiFile: PsiFile, line: Int): PsiClass? {
        val element = elementAtLine(psiFile, line) ?: return null
        return runReadAction {
            PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
        }
    }

    fun findMethodAtLine(psiFile: PsiFile, line: Int): PsiMethod? {
        val element = elementAtLine(psiFile, line) ?: return null
        return runReadAction {
            PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        }
    }

    fun formatMethodSignature(method: PsiMethod): String {
        val returnType = method.returnType?.presentableText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") {
            "${it.type.presentableText} ${it.name}"
        }
        return "$returnType ${method.name}($params)"
    }

    fun getLineNumber(file: PsiFile, offset: Int): Int {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
            ?: return 0
        return document.getLineNumber(offset) + 1
    }

    fun getSurroundingLines(file: PsiFile, offset: Int, surroundingLines: Int): String {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
            ?: return ""
        val lineNumber = document.getLineNumber(offset)
        val startLine = (lineNumber - surroundingLines).coerceAtLeast(0)
        val endLine = (lineNumber + surroundingLines).coerceAtMost(document.lineCount - 1)
        val startOffset = document.getLineStartOffset(startLine)
        val endOffset = document.getLineEndOffset(endLine)
        return document.text.substring(startOffset, endOffset)
    }

    fun getFilePath(file: PsiFile, project: Project): String {
        val basePath = project.basePath ?: return file.virtualFile.path
        val fullPath = file.virtualFile.path
        return if (fullPath.startsWith(basePath)) {
            fullPath.removePrefix(basePath).removePrefix("/")
        } else {
            fullPath
        }
    }

    fun toRelativePath(filePath: String, basePath: String): String {
        return if (filePath.startsWith(basePath)) {
            filePath.removePrefix(basePath).removePrefix("/")
        } else {
            filePath.substringAfterLast("/")
        }
    }
}
