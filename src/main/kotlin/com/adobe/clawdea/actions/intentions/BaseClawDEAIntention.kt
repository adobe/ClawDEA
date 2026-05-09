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
package com.adobe.clawdea.actions.intentions

import com.adobe.clawdea.actions.ActionExecutor
import com.adobe.clawdea.actions.ActionType
import com.adobe.clawdea.actions.ResultRenderer
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.psi.PsiFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

abstract class BaseClawDEAIntention : PsiElementBaseIntentionAction() {

    abstract val actionType: ActionType
    abstract val actionName: String
    protected open val needsUserInstructions: Boolean = false

    override fun getFamilyName(): String = "ClawDEA"

    override fun getText(): String = actionName

    // invoke() opens modal dialogs (Ask/Refactor) and schedules background CLI work; it does
    // not modify PSI directly, so it must not run inside the platform's default write action.
    override fun startInWriteAction(): Boolean = false

    /**
     * ClawDEA intentions fire an async CLI request (and sometimes an EDT-only input dialog),
     * so the framework must not dry-run invoke() to build a preview. Returning a small Html
     * preview also prevents the fallback lookup of intentionDescriptions/<ClassName>/description.html,
     * which we intentionally do not ship.
     */
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.Html(HtmlChunk.text(previewDescription))

    protected open val previewDescription: String
        get() = "Sends the selected code to Claude and shows the result."

    override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
        return editor.selectionModel.hasSelection()
    }

    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        val selectedText = editor.selectionModel.selectedText ?: return
        val userInstructions = getUserInstructions(project)

        // If this action needs user input and user cancelled the dialog, abort
        if (needsUserInstructions && userInstructions == null) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "ClawDEA: ${actionName}...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                val result = runBlockingCancellable {
                    ActionExecutor.execute(project, editor, actionType, selectedText, userInstructions)
                }

                ApplicationManager.getApplication().invokeLater {
                    when {
                        result.text.isNotBlank() && actionType.isCodeAction ->
                            ResultRenderer.showDiff(project, editor, selectedText, result.text, actionName)
                        result.text.isNotBlank() ->
                            ResultRenderer.showBalloon(editor, result.text)
                        else ->
                            ResultRenderer.showBalloon(editor, "ClawDEA: ${result.errorMessage ?: "No response."}")
                    }
                }
            }
        })
    }

    protected open fun getUserInstructions(project: Project): String? = null
}
