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

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Confirmation dialog shown when an OpenAI-compatible request fails AFTER the turn already produced
 * partial output (streamed text) or executed tools. Because a bounded auto-retry could double-charge
 * or re-run side effects, the retry policy defers to the user via this prompt.
 *
 * Retry (OK) reuses [com.adobe.clawdea.provider.openai.agent.ConversationState.completedToolCallIds],
 * so already-completed tool calls are returned from the ledger rather than executed again. Cancel
 * preserves the partial transcript; the backend emits one terminal error result.
 */
class PartialRetryPrompt(
    project: Project,
    private val profileName: String,
    private val modelId: String,
    private val emittedText: Boolean,
    private val toolsCompleted: Boolean,
) : DialogWrapper(project, true) {

    init {
        title = "Request interrupted — retry?"
        setOKButtonText("Retry")
        setCancelButtonText("Cancel")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)

        panel.add(JLabel("The request to \"$profileName\" ($modelId) failed after producing output."))
        panel.add(JLabel(" "))
        panel.add(JLabel("Text emitted:      ${if (emittedText) "yes" else "no"}"))
        panel.add(JLabel("Tools completed:   ${if (toolsCompleted) "yes" else "no"}"))
        panel.add(JLabel(" "))
        panel.add(JLabel("Retry re-issues the request. Completed tool calls are reused, not re-run."))
        return panel
    }
}
