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
package com.adobe.clawdea.provider.openai.session

import com.google.gson.JsonObject

/**
 * A single event in an OpenAI-compatible chat session ledger. Ledgers are append-only JSONL files
 * (one record per line) storing the full conversation history + metadata for later resume/replay.
 *
 * Supported types:
 *  - `meta`: session metadata (sessionId, profileId, project path, model, creation timestamp)
 *  - `user`: user message content
 *  - `assistant`: assistant text response
 *  - `reasoning`: reasoning block summary (buffered deltas → one summary per completed block)
 *  - `tool_use`: tool call request from assistant
 *  - `tool_result`: tool execution result
 *  - `usage`: token usage snapshot
 */
data class SessionLedgerRecord(
    val schemaVersion: Int = 1,
    val type: String,
    val timestamp: String,
    val payload: JsonObject,
)
