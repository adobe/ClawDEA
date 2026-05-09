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
package com.adobe.clawdea.chat.permission

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * One outstanding permission prompt: posted by the MCP handler, resolved by
 * the UI, then reclaimed by the handler. [latch] unblocks once [decision] is set.
 *
 * [updatedInput] carries an optional modified input payload that the UI can
 * supply when resolving (used by AskUserQuestion to fold the user's answers
 * back into the tool input before the CLI runs the tool).
 */
class PermissionRequest(
    val requestId: String,
    val toolName: String,
    val inputJson: String,
    val summary: String,
) {
    val latch: CountDownLatch = CountDownLatch(1)
    private val decisionRef: AtomicReference<Decision?> = AtomicReference(null)
    private val updatedInputRef: AtomicReference<String?> = AtomicReference(null)

    val decision: Decision? get() = decisionRef.get()
    val updatedInput: String? get() = updatedInputRef.get()

    /** Returns true if this call recorded the decision; false if one was already set. */
    fun resolve(decision: Decision, updatedInput: String? = null): Boolean {
        val set = decisionRef.compareAndSet(null, decision)
        if (set) {
            updatedInputRef.set(updatedInput)
            latch.countDown()
        }
        return set
    }

    enum class Decision { ALLOW, DENY }
}
