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
package com.adobe.clawdea.commands.handlers

import com.adobe.clawdea.chat.IndexQueryHandler
import com.adobe.clawdea.commands.CommandContext
import com.adobe.clawdea.commands.CommandHandler
import com.adobe.clawdea.commands.CommandInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor

class IndexQueryCommandHandler(
    override val info: CommandInfo,
    private val indexQueryHandler: IndexQueryHandler,
    private val editorProvider: () -> Editor?,
) : CommandHandler {
    override fun execute(args: String, context: CommandContext) {
        val editor = editorProvider() ?: return
        val offset = editor.caretModel.offset
        ApplicationManager.getApplication().executeOnPooledThread {
            val html = indexQueryHandler.handleCommand(info.name, editor, offset)
            ApplicationManager.getApplication().invokeLater {
                context.appendHtml(html)
            }
        }
    }
}
