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

import com.adobe.clawdea.commands.*
import com.adobe.clawdea.skills.SkillInfo
import kotlin.io.path.readText

class SkillHandler(
    private val skillInfo: SkillInfo,
    private val sendToBridge: (String) -> Unit,
    private val probeResult: () -> Boolean,
) : CommandHandler {

    override val info = CommandInfo(
        name = skillInfo.aliases.first(),
        description = skillInfo.description,
        category = CommandCategory.SKILL,
    )

    override fun execute(args: String, context: CommandContext) {
        if (probeResult()) {
            // CLI handles skills natively — forward the short name for
            // user/project skills (the CLI doesn't know synthetic prefixes),
            // qualified name for plugin-cache skills.
            val isSyntheticPlugin = skillInfo.pluginName == "user" || skillInfo.pluginName == "project"
            val skillRef = if (isSyntheticPlugin) skillInfo.name else skillInfo.qualifiedName
            val command = "/$skillRef" + if (args.isNotBlank()) " $args" else ""
            sendToBridge(command)
        } else {
            // Fallback: inject skill markdown as a user message
            val message = buildFallbackMessage(skillInfo, args)
            sendToBridge(message)
        }
    }

    companion object {
        fun buildFallbackMessage(skillInfo: SkillInfo, args: String): String {
            val content = skillInfo.filePath.readText()
            return buildString {
                appendLine("<command-name>${skillInfo.qualifiedName}</command-name>")
                appendLine("<command-args>$args</command-args>")
                appendLine()
                appendLine(content)
                appendLine()
                appendLine("ARGUMENTS: $args")
            }
        }
    }
}
