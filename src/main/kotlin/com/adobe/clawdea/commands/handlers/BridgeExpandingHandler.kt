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

import com.adobe.clawdea.commands.CommandContext
import com.adobe.clawdea.commands.CommandHandler
import com.adobe.clawdea.commands.CommandInfo

/**
 * Handler for ClawDEA-invented slash commands that don't exist in the Claude Code
 * CLI itself. The handler runs locally to render a UI placeholder, then provides
 * an [expand] template — the chat send pipeline substitutes the expanded text for
 * the original slash command before forwarding to the CLI as a regular user message.
 *
 * Contrast with [BridgeForwardHandler], which sends the slash command verbatim and
 * therefore only works for CLI-built-in commands like `/init`, `/cost`, `/compact`.
 */
class BridgeExpandingHandler(
    override val info: CommandInfo,
    private val expansion: (args: String) -> String,
) : CommandHandler {
    override fun execute(args: String, context: CommandContext) {
        val safeName = info.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        context.appendHtml("""<div class="info-block">Expanding $safeName...</div>""")
    }

    fun expand(args: String): String = expansion(args)
}
