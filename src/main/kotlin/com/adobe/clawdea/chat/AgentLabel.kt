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
package com.adobe.clawdea.chat

import com.adobe.clawdea.auth.AuthManager
import com.adobe.clawdea.cli.CliBridge

/**
 * Human-readable name of the active chat agent, so UI chrome (assistant bubble
 * label, "… is thinking" status, input placeholder) reflects the backend the
 * user actually selected instead of hardcoding "Claude".
 */
object AgentLabel {

    /** Label for the currently-effective provider. Reads app-level settings; call on EDT / after app init. */
    fun current(): String = forProvider(AuthManager.getInstance().effectiveProviderId())

    /** "Codex" for the OpenAI (codex) backends, "Claude" otherwise. */
    fun forProvider(providerId: String): String =
        if (CliBridge.isCodexProvider(providerId)) "Codex" else "Claude"
}
