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
package com.adobe.clawdea.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane

object ResultRenderer {

    fun showBalloon(editor: Editor, text: String) {
        val htmlContent = markdownToHtml(text)

        val editorPane = JEditorPane("text/html", htmlContent).apply {
            isEditable = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        }

        val scrollPane = JScrollPane(editorPane).apply {
            preferredSize = java.awt.Dimension(500, 300)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, editorPane)
            .setTitle("ClawDEA")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()

        val point = editor.visualPositionToXY(editor.caretModel.visualPosition)
        popup.show(RelativePoint(editor.contentComponent, point))
    }

    /**
     * Show a diff between the user's selection and Claude's suggestion in a modal
     * dialog with Apply/Cancel buttons. The right (proposed) side is editable, so
     * users can tweak the suggestion before applying. Apply replaces the editor
     * selection with the right-side content.
     */
    fun showDiff(project: Project, editor: Editor, originalText: String, newText: String, title: String) {
        val fileType = editor.virtualFile?.let {
            FileTypeManager.getInstance().getFileTypeByFile(it)
        }

        val contentFactory = DiffContentFactory.getInstance()
        val originalContent = contentFactory.create(project, originalText, fileType)
        val proposedContent = contentFactory.createEditable(project, newText, fileType)
        val proposedDocument = proposedContent.document

        val request = SimpleDiffRequest(
            "ClawDEA: $title",
            originalContent,
            proposedContent,
            "Original",
            "Claude's suggestion (editable)",
        )

        val diffPanel = DiffManager.getInstance().createRequestPanel(project, {}, null)
        diffPanel.setRequest(request)

        // Capture selection bounds now — the dialog is modal, but the selection
        // model may be cleared by focus changes before we read it back.
        val selectionStart = editor.selectionModel.selectionStart
        val selectionEnd = editor.selectionModel.selectionEnd

        val dialog = DiffApplyDialog(project, diffPanel.component, title)
        if (dialog.showAndGet()) {
            val finalText = proposedDocument.text
            WriteCommandAction.runWriteCommandAction(project, "ClawDEA: $title", null, {
                editor.document.replaceString(selectionStart, selectionEnd, finalText)
            })
        }
    }

    fun applyReplacement(project: Project, editor: Editor, newText: String) {
        val selectionModel = editor.selectionModel
        val start = selectionModel.selectionStart
        val end = selectionModel.selectionEnd

        WriteCommandAction.runWriteCommandAction(project, "ClawDEA Action", null, {
            editor.document.replaceString(start, end, newText)
        })
    }

    private fun markdownToHtml(markdown: String): String {
        var html = markdown
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // Code blocks
        html = html.replace(Regex("```(?:\\w+)?\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)) {
            "<pre><code>${it.groupValues[1]}</code></pre>"
        }

        // Inline code
        html = html.replace(Regex("`([^`]+)`")) {
            "<code>${it.groupValues[1]}</code>"
        }

        // Headers
        html = html.replace(Regex("^### (.+)$", RegexOption.MULTILINE)) { "<h3>${it.groupValues[1]}</h3>" }
        html = html.replace(Regex("^## (.+)$", RegexOption.MULTILINE)) { "<h2>${it.groupValues[1]}</h2>" }
        html = html.replace(Regex("^# (.+)$", RegexOption.MULTILINE)) { "<h1>${it.groupValues[1]}</h1>" }

        // Bold and italic
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
        html = html.replace(Regex("\\*(.+?)\\*")) { "<i>${it.groupValues[1]}</i>" }

        // Line breaks
        html = html.replace("\n", "<br>")

        return "<html><body style='padding:8px;'>$html</body></html>"
    }
}

/**
 * Modal dialog wrapping a diff panel with Apply/Cancel buttons. OK = Apply.
 */
private class DiffApplyDialog(
    project: Project,
    private val diffComponent: JComponent,
    title: String,
) : DialogWrapper(project, true) {

    init {
        this.title = "ClawDEA: $title"
        setOKButtonText("Apply")
        setCancelButtonText("Cancel")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(900, 600)
        panel.add(diffComponent, BorderLayout.CENTER)
        return panel
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)
}
