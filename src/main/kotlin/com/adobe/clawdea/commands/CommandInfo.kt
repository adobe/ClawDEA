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
package com.adobe.clawdea.commands

enum class CommandCategory {
    LOCAL, BRIDGE, INDEX, SKILL, DIALOG
}

data class CommandInfo(
    val name: String,
    val description: String,
    val category: CommandCategory,
)

data class CommandMatch(
    val handler: CommandHandler,
    val args: String,
)

data class CommandContext(
    val appendHtml: (String) -> Unit,
    val showNotification: (String) -> Unit,
    /**
     * Optional in-plugin question prompt. Non-null when the surrounding chat
     * panel can render an AskUserQuestion card; null in headless/test contexts
     * (and the handler should bail with a clear message).
     *
     * The callback fires `onResolve` with the user's answers map and freeform
     * inputs map; `onResolve(null)` indicates the user clicked Skip / cancelled.
     */
    val askQuestion: ((
        input: com.adobe.clawdea.chat.permission.AskUserQuestionInput,
        onResolve: (com.adobe.clawdea.chat.permission.HandlerQuestionAnswers?) -> Unit,
    ) -> Unit)? = null,
    /**
     * Optional bridge dispatch — sends [text] to the CLI as a regular user
     * message without rendering it in the chat. Non-null when invoked from
     * the chat panel; null in headless/test contexts.
     *
     * Used by handlers that need to delay or modify the prompt sent to the
     * model (e.g. /seed-wiki gathers a placement choice from the user before
     * dispatching a path-aware expanded prompt).
     */
    val dispatchToBridge: ((text: String) -> Unit)? = null,
)

interface CommandHandler {
    val info: CommandInfo
    fun execute(args: String, context: CommandContext)
}
