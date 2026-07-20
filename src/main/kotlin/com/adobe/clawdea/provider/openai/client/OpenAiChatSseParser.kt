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
package com.adobe.clawdea.provider.openai.client

import com.adobe.clawdea.gateway.StreamEvent
import com.google.gson.JsonParser

/**
 * Parses OpenAI Chat Completions SSE lines into [StreamEvent].
 * Handles `choices[0].delta.content`, `choices[0].finish_reason`,
 * top-level `error.message`, and `[DONE]`.
 */
class OpenAiChatSseParser {

    fun parseLine(line: String): StreamEvent? {
        if (!line.startsWith("data: ")) return null
        val payload = line.removePrefix("data: ").trim()
        if (payload == "[DONE]") return StreamEvent.MessageStop(null)

        return try {
            val root = JsonParser.parseString(payload)
            if (!root.isJsonObject) return null
            val obj = root.asJsonObject

            // Check for top-level error
            val error = obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
            if (error != null) {
                val message = error.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: "Unknown error"
                return StreamEvent.Error(message)
            }

            // Parse choices[0]
            val choices = obj.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return null
            if (choices.size() == 0) return null
            val firstChoice = choices[0]?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return null

            // Check for finish_reason
            val finishReason = firstChoice.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString
            if (finishReason != null && finishReason != "null") {
                return StreamEvent.MessageStop(finishReason)
            }

            // Parse delta.content
            val delta = firstChoice.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return null
            val content = delta.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                ?: return null

            StreamEvent.TextDelta(content)
        } catch (_: Exception) {
            null
        }
    }
}
