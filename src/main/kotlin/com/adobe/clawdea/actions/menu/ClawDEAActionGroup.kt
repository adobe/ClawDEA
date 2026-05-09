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
package com.adobe.clawdea.actions.menu

import com.adobe.clawdea.actions.ActionExecutor
import com.adobe.clawdea.actions.ActionType
import com.adobe.clawdea.actions.ResultRenderer
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.ui.Messages

class ClawDEAActionGroup : ActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        return arrayOf(
            ClawDEAContextMenuAction("Explain this code", ActionType.EXPLAIN),
            ClawDEAContextMenuAction("Optimize this", ActionType.OPTIMIZE),
            ClawDEAContextMenuAction("Generate unit test", ActionType.GENERATE_TEST),
            ClawDEAContextMenuAction("Check for security issues", ActionType.SECURITY_CHECK),
            ClawDEAContextMenuAction("Add documentation", ActionType.ADD_DOCUMENTATION),
            ClawDEAContextMenuAction("Refactor with instructions...", ActionType.REFACTOR, needsInput = true),
            ClawDEAContextMenuAction("Ask Claude about this...", ActionType.ASK_CLAUDE, needsInput = true),
        )
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible = hasSelection
    }
}

class ClawDEAContextMenuAction(
    private val actionName: String,
    private val actionType: ActionType,
    private val needsInput: Boolean = false,
) : AnAction(actionName) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText ?: return

        val userInstructions = if (needsInput) {
            val dialogTitle = if (actionType == ActionType.REFACTOR) {
                "Describe how you want this code refactored:"
            } else {
                "What would you like to ask about this code?"
            }
            Messages.showInputDialog(project, dialogTitle, "ClawDEA: $actionName", null) ?: return
        } else {
            null
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "ClawDEA: $actionName...", true) {
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

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }
}
