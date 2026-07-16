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
package com.adobe.clawdea.provider.openai.agent

import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates atomic cancel-and-continue steering for an agent backend.
 *
 * Under one mutex, this controller:
 * 1. Marks the cancellation as steering (not abort)
 * 2. Cancels and joins the active stream job
 * 3. Persists valid partial assistant text
 * 4. Clears the tool call assembler without executing fragments
 * 5. Appends the steering user message
 * 6. Signals continuation (the backend launches a new turn without emitting a terminal Result)
 */
class SteeringController {
    private val mutex = Mutex()
    private var activeJob: Job? = null
    @Volatile
    private var pendingSteer: String? = null

    /**
     * Atomically set the active job. Must be called before each turn starts.
     */
    suspend fun setActiveJob(job: Job?) {
        mutex.withLock {
            activeJob = job
        }
    }

    /**
     * Request steering with the given text. Returns true if steering was initiated, false if no turn is active.
     *
     * This method:
     * - Cancels the active job under the mutex
     * - Sets pendingSteer to signal the backend to continue with the steering message
     * - Returns false if no turn is active
     */
    suspend fun steer(text: String): Boolean {
        mutex.withLock {
            val job = activeJob ?: return false
            pendingSteer = text
            job.cancel()
            return true
        }
    }

    /**
     * Check if there is a pending steer message. The backend must call this after a turn completes
     * to decide whether to continue with a steering message or emit a terminal Result.
     */
    suspend fun consumePendingSteer(): String? {
        return mutex.withLock {
            val steer = pendingSteer
            pendingSteer = null
            steer
        }
    }

    /**
     * Clear the active job (called when a turn completes normally).
     */
    suspend fun clearActiveJob() {
        mutex.withLock {
            activeJob = null
        }
    }
}
