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
package com.adobe.clawdea.knowledge.drift

import java.time.Instant

data class DreamDueDecision(val due: Boolean, val reasons: List<String>)

/**
 * Stub: Dream wiki maintenance is being removed (Task 12 of the wiki maintenance
 * redesign). The body referenced state.dream* fields that have been deleted; the
 * gate now always reports "not-due:disabled". The file itself is deleted in Task 12.
 */
object DreamDueGate {

    fun evaluate(
        enabled: Boolean,
        now: Instant,
        state: DriftState,
        minElapsedHours: Int,
        minSignalUnits: Int,
        scanThrottleMinutes: Int,
        activeTurn: Boolean,
        lockHeld: Boolean,
    ): DreamDueDecision = DreamDueDecision(due = false, reasons = listOf("disabled"))
}
