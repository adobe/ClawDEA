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
package com.adobe.clawdea.actions.quickfix

import com.adobe.clawdea.actions.ActionExecutor
import com.adobe.clawdea.actions.ActionType
import com.adobe.clawdea.actions.ResultRenderer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

class FixWithClaudeIntention : IntentionAction {

    override fun getText(): String = "Fix with Claude (ClawDEA)"

    override fun getFamilyName(): String = "ClawDEA"

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        return getDiagnosticsAtCaret(project, editor, file).isNotEmpty()
    }

    // Async CLI call + diff UI — no static preview. A non-EMPTY Html preview also avoids the
    // fallback lookup of intentionDescriptions/<ClassName>/description.html.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.Html(HtmlChunk.text("Sends the diagnostic and surrounding code to Claude for a fix."))

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val diagnostics = getDiagnosticsAtCaret(project, editor, file)
        if (diagnostics.isEmpty()) return

        val diagnosticMessage = diagnostics.joinToString("; ") { it.description ?: "Unknown issue" }

        // Get the text range covering all diagnostics
        val startOffset = diagnostics.minOf { it.startOffset }
        val endOffset = diagnostics.maxOf { it.endOffset }
        val document = editor.document
        val selectedText = document.getText(TextRange(startOffset, endOffset))

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "ClawDEA: Fixing...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                val result = runBlockingCancellable {
                    ActionExecutor.execute(
                        project = project,
                        editor = editor,
                        actionType = ActionType.FIX,
                        selectedText = selectedText,
                        userInstructions = diagnosticMessage,
                    )
                }

                ApplicationManager.getApplication().invokeLater {
                    if (result.text.isNotBlank()) {
                        ResultRenderer.showDiff(project, editor, selectedText, result.text, "Fix with Claude")
                    } else {
                        ResultRenderer.showBalloon(editor, "ClawDEA: ${result.errorMessage ?: "No response."}")
                    }
                }
            }
        })
    }

    override fun startInWriteAction(): Boolean = false

    private fun getDiagnosticsAtCaret(project: Project, editor: Editor, file: PsiFile): List<HighlightInfo> {
        val offset = editor.caretModel.offset
        val document = editor.document
        val result = mutableListOf<HighlightInfo>()
        DaemonCodeAnalyzerEx.processHighlights(
            document, project, HighlightSeverity.WARNING, 0, document.textLength
        ) { info ->
            if (info.startOffset <= offset && offset <= info.endOffset) {
                result.add(info)
            }
            true
        }
        return result
    }
}
