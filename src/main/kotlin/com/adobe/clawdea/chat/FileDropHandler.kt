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

import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.JTextArea
import javax.swing.TransferHandler

class FileDropHandler(
    private val projectBasePath: String?,
    private val onBeforeInsert: (() -> Unit)? = null,
) : TransferHandler() {

    override fun canImport(support: TransferSupport): Boolean {
        return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
    }

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false

        val transferable = support.transferable
        @Suppress("UNCHECKED_CAST")
        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
            ?: return false

        val paths = files
            .filter { it.isFile }
            .map { toRelativePath(it.absolutePath, projectBasePath) }

        val textArea = support.component as? JTextArea ?: return false

        if (paths.isEmpty()) return false

        onBeforeInsert?.invoke()

        val references = formatReferences(paths)
        val pos = textArea.caretPosition.coerceIn(0, textArea.text.length)
        val insertText = buildInsertText(textArea.text, pos, references)
        textArea.insert(insertText, pos)
        textArea.caretPosition = pos + insertText.length

        return true
    }

    companion object {
        fun toRelativePath(absolutePath: String, basePath: String?): String {
            if (basePath == null) return absolutePath
            val normalizedBase = basePath.trimEnd('/')
            return if (absolutePath.startsWith("$normalizedBase/")) {
                absolutePath.removePrefix("$normalizedBase/")
            } else {
                absolutePath
            }
        }

        fun formatReferences(paths: List<String>): String {
            return paths.joinToString(" ") { "@`$it`" }
        }

        fun buildInsertText(existingText: String, caretPosition: Int, references: String): String {
            if (references.isEmpty()) return ""

            val needsLeadingSpace = caretPosition > 0
                && caretPosition <= existingText.length
                && !existingText[caretPosition - 1].isWhitespace()

            val needsTrailingSpace = caretPosition >= existingText.length
                || !existingText[caretPosition].isWhitespace()

            val prefix = if (needsLeadingSpace) " " else ""
            val suffix = if (needsTrailingSpace) " " else ""
            return "$prefix$references$suffix"
        }
    }
}
