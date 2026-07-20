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
package com.adobe.clawdea.cli.backend

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.skills.SkillInfo

/**
 * Normalized contract for all agent backend implementations (process-based, HTTP-based).
 * Hides the transport details (stdio, HTTP) behind a common event-driven interface.
 */
interface AgentBackend {
    val isAlive: Boolean
    val backendKind: BackendKind
    val agentLabel: String
    val steeringMode: SteeringMode

    fun start(resumeSessionId: String? = null, skills: List<SkillInfo> = emptyList())
    fun readEvent(): CliEvent?
    fun sendMessage(text: String)
    fun abort()
    fun steer(text: String): Boolean
    fun stop()
    fun recentErrors(): List<String>
}

/**
 * Steering capability mode for a backend.
 *  - NONE: no mid-turn steering (Claude CLI — SIGINT ends the turn).
 *  - NATIVE: backend-native steer (Codex `turn/steer`).
 *  - CANCEL_AND_CONTINUE: HTTP backend cancels the running turn and starts a new one with
 *    the combined message (future HTTP agent backend implementation).
 */
enum class SteeringMode {
    NONE,
    NATIVE,
    CANCEL_AND_CONTINUE
}
