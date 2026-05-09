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
// src/main/kotlin/com/adobe/clawdea/gateway/ApiModels.kt
package com.adobe.clawdea.gateway

/**
 * Data classes for the Anthropic Messages API.
 * See: https://docs.anthropic.com/en/api/messages
 */

data class MessageRequest(
    val model: String,
    val max_tokens: Int,
    val messages: List<Message>,
    val system: String? = null,
    val stream: Boolean = true,
)

data class Message(
    val role: String,
    val content: String,
)

/**
 * Represents a single SSE event from the streaming API.
 */
sealed class StreamEvent {
    /** A chunk of text content */
    data class TextDelta(val text: String) : StreamEvent()
    /** The message is complete */
    data class MessageStop(val stopReason: String?) : StreamEvent()
    /** An error occurred */
    data class Error(val message: String) : StreamEvent()
    /** Content block started */
    data class ContentBlockStart(val index: Int) : StreamEvent()
    /** Content block stopped */
    data class ContentBlockStop(val index: Int) : StreamEvent()
    /** Ping/keepalive — ignore */
    object Ping : StreamEvent()
}

/**
 * Configuration for a gateway request.
 */
data class GatewayRequest(
    val model: String,
    val maxTokens: Int,
    val systemPrompt: String?,
    val userMessage: String,
    val timeoutSeconds: Long = 30,
)
