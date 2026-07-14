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
package com.adobe.clawdea.auth

import com.intellij.util.messages.Topic

/**
 * Codex (OpenAI ChatGPT subscription) auth events. Kept on a DISTINCT [TOPIC] from
 * [SubscriptionAuthEventListener] so the Claude subscription card does not react to codex
 * sign-in/out and vice versa. [com.adobe.clawdea.chat.ModelComboManager] subscribes to both
 * topics to refresh the model catalog on either provider's sign-in.
 */
interface CodexSubscriptionAuthEventListener {
    fun onAuthFailed(reason: String) {}
    fun onStatusChanged(status: AuthStatus) {}

    companion object {
        val TOPIC: Topic<CodexSubscriptionAuthEventListener> = Topic.create(
            "ClawDEA Codex Subscription Auth",
            CodexSubscriptionAuthEventListener::class.java,
        )
    }
}
