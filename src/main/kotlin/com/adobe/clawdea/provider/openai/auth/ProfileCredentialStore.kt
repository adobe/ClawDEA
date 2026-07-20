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
package com.adobe.clawdea.provider.openai.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-profile credential storage backed by [PasswordSafe], with an EDT-safe read-through cache.
 *
 * Reading [PasswordSafe] can block (keychain access) and is a forbidden slow operation on the
 * event-dispatch thread — mirrors [com.adobe.clawdea.settings.ClawDEASettings] `getSecret`. Because
 * auth-state consumers such as the chat model dropdown and the Roles tab evaluate credentials
 * *synchronously on the EDT*, a bare "return empty on EDT" would permanently mislabel a configured
 * profile as unauthenticated. So [get] serves from a process-wide cache on the EDT (populated by the
 * first off-EDT read — the startup model probe warms the active profile) and only touches
 * [PasswordSafe] off the EDT. [set]/[clear] update the cache synchronously so a profile the user just
 * configured is immediately known, even on the EDT.
 *
 * The [cache] is injectable so tests can isolate; production shares [sharedCache].
 */
open class ProfileCredentialStore(
    private val read: (CredentialAttributes) -> String? = { PasswordSafe.instance.getPassword(it) },
    private val write: (CredentialAttributes, String?) -> Unit = { attr, value ->
        PasswordSafe.instance.setPassword(attr, value)
    },
    private val cache: ConcurrentHashMap<String, String> = sharedCache,
) {
    open fun get(profileId: String): String {
        val attr = attributes(profileId)
        cache[attr.serviceName]?.let { return it }
        // Never block on PasswordSafe from the EDT. A cache miss here reports "not configured" until a
        // background read (e.g. the model probe) warms the cache — a transient, self-healing state.
        if (java.awt.EventQueue.isDispatchThread()) return ""
        return cache.getOrPut(attr.serviceName) { read(attr).orEmpty() }
    }

    open fun set(profileId: String, credential: String) {
        val attr = attributes(profileId)
        cache[attr.serviceName] = credential.ifBlank { "" }
        write(attr, credential.ifBlank { null })
    }

    fun clear(profileId: String) {
        val attr = attributes(profileId)
        cache[attr.serviceName] = ""
        write(attr, null)
    }

    private fun attributes(profileId: String) = CredentialAttributes(
        generateServiceName("ClawDEA", "openai-compatible/profile/$profileId/credential"),
    )

    companion object {
        /** Process-wide credential cache shared by all default-constructed stores. */
        private val sharedCache = ConcurrentHashMap<String, String>()
    }
}
