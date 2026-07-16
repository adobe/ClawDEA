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
package com.adobe.clawdea.chat.session

import com.adobe.clawdea.provider.openai.session.OpenAiSessionScanner

/**
 * Unifies the three per-backend session stores ([SessionScanner] for Claude, [CodexSessionScanner]
 * for codex, [OpenAiSessionScanner] for OpenAI-compatible providers) so the resume picker,
 * auto-resume, and view-reload treat sessions uniformly regardless of which CLI produced them.
 * Origin travels on each [SessionInfo]; downstream resume logic uses it to decide native resume vs
 * cross-backend transcript replay.
 */
object SessionCatalog {

    /** All sessions for the project from all stores, newest first. */
    fun scanAll(projectBasePath: String): List<SessionInfo> =
        (SessionScanner.scan(projectBasePath) +
            CodexSessionScanner.scan(projectBasePath) +
            OpenAiSessionScanner.scan(projectBasePath))
            .sortedByDescending { it.timestamp }

    /** The single most-recent session across all stores (for auto-resume), or null if none. */
    fun mostRecent(projectBasePath: String): SessionInfo? = scanAll(projectBasePath).firstOrNull()

    /** Which store holds [sessionId], or null if none does. */
    fun resolveOrigin(projectBasePath: String, sessionId: String): SessionOrigin? = when {
        SessionScanner.hasSessionFile(projectBasePath, sessionId) -> SessionOrigin.CLAUDE
        CodexSessionScanner.hasSession(projectBasePath, sessionId) -> SessionOrigin.CODEX
        OpenAiSessionScanner.hasSession(projectBasePath, sessionId) -> SessionOrigin.OPENAI_COMPATIBLE
        else -> null
    }

    fun loadHistory(projectBasePath: String, sessionId: String, origin: SessionOrigin): List<HistoryEntry> =
        when (origin) {
            SessionOrigin.CLAUDE -> SessionScanner.loadHistory(projectBasePath, sessionId)
            SessionOrigin.CODEX -> CodexSessionScanner.loadHistory(projectBasePath, sessionId)
            SessionOrigin.OPENAI_COMPATIBLE -> OpenAiSessionScanner.loadHistory(projectBasePath, sessionId)
        }
}
