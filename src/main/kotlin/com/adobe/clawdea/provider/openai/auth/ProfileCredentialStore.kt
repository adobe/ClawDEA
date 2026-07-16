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

open class ProfileCredentialStore(
    private val read: (CredentialAttributes) -> String? = { PasswordSafe.instance.getPassword(it) },
    private val write: (CredentialAttributes, String?) -> Unit = { attr, value ->
        PasswordSafe.instance.setPassword(attr, value)
    },
) {
    open fun get(profileId: String): String = read(attributes(profileId)).orEmpty()

    open fun set(profileId: String, credential: String) =
        write(attributes(profileId), credential.ifBlank { null })

    fun clear(profileId: String) = write(attributes(profileId), null)

    private fun attributes(profileId: String) = CredentialAttributes(
        generateServiceName("ClawDEA", "openai-compatible/profile/$profileId/credential"),
    )
}
