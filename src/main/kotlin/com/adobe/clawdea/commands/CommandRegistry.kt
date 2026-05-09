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

import com.intellij.openapi.diagnostic.Logger

class CommandRegistry {

    private val log = Logger.getInstance(CommandRegistry::class.java)
    private val handlers = mutableMapOf<String, CommandHandler>()

    fun register(name: String, handler: CommandHandler) {
        val key = name.lowercase()
        val existing = handlers[key]
        if (existing != null && existing !== handler) {
            log.warn("Command $key re-registered: ${existing.info.description} → ${handler.info.description}")
        }
        handlers[key] = handler
    }

    fun unregister(name: String) {
        handlers.remove(name.lowercase())
    }

    fun resolve(input: String): CommandMatch? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null

        val parts = trimmed.split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val args = parts.getOrNull(1)?.trim() ?: ""

        val handler = handlers[cmd] ?: return null
        return CommandMatch(handler, args)
    }

    fun allCommands(): List<CommandInfo> {
        // Deduplicate: same handler may be registered under multiple aliases
        return handlers.values.distinct().map { it.info }
    }
}
