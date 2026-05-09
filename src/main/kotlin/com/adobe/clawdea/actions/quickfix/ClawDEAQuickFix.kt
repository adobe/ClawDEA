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
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager

class ClawDEAQuickFix(private val diagnosticMessage: String) : LocalQuickFix {

    override fun getFamilyName(): String = "ClawDEA"

    override fun getName(): String = "Fix with Claude"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val psiElement = descriptor.psiElement ?: return
        val psiFile = psiElement.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

        val contextElement = psiElement.parent ?: psiElement
        val startOffset = contextElement.textRange.startOffset
        val endOffset = contextElement.textRange.endOffset
        val selectedText = document.getText(TextRange(startOffset, endOffset))

        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return

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
}
