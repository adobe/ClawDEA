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
package com.adobe.clawdea.debug

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class SuspendInfo(
    val file: String?,
    val line: Int,
    val method: String?,
    val exitCode: Int = -1,
)

class SuspendGate {

    @Volatile
    private var future: CompletableFuture<SuspendInfo>? = null

    fun arm() {
        future?.cancel(false)
        future = CompletableFuture()
    }

    fun disarm() {
        future?.cancel(false)
        future = null
    }

    fun awaitSuspend(timeout: Duration): SuspendInfo? {
        val f = future ?: return null
        return try {
            f.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun onSuspended(info: SuspendInfo) {
        future?.complete(info)
    }

    fun onSessionEnded(exitCode: Int) {
        future?.complete(SuspendInfo(null, -1, null, exitCode))
    }
}
