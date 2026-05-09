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

import java.time.Duration
import java.time.Instant

data class DreamDueDecision(val due: Boolean, val reasons: List<String>)

object DreamDueGate {

    fun evaluate(
        knowledgeLayerEnabled: Boolean,
        dreamMaintenanceEnabled: Boolean,
        now: Instant,
        state: DriftState,
        minElapsedHours: Int,
        minSignalUnits: Int,
        scanThrottleMinutes: Int,
        activeTurn: Boolean,
        lockHeld: Boolean,
    ): DreamDueDecision {
        val reasons = mutableListOf<String>()
        if (!knowledgeLayerEnabled) reasons += "knowledge-layer-disabled"
        if (!dreamMaintenanceEnabled) reasons += "dream-maintenance-disabled"
        if (activeTurn) reasons += "active-turn"
        if (lockHeld) reasons += "lock-held"
        if (!hasElapsed(state.dreamLastSuccessfulScanAt, now, Duration.ofHours(minElapsedHours.toLong()))) {
            reasons += "elapsed-time"
        }
        if (minSignalUnits > 0 && state.dreamObservedSignalUnits - state.dreamProcessedSignalUnits < minSignalUnits) {
            reasons += "insufficient-signal"
        }
        if (!hasElapsed(state.dreamLastDueCheckAt, now, Duration.ofMinutes(scanThrottleMinutes.toLong()))) {
            reasons += "scan-throttle"
        }
        return DreamDueDecision(due = reasons.isEmpty(), reasons = reasons)
    }

    private fun hasElapsed(timestamp: String, now: Instant, threshold: Duration): Boolean {
        if (threshold.isZero || threshold.isNegative) return true
        val last = try {
            Instant.parse(timestamp)
        } catch (_: Exception) {
            return true
        }
        return !Duration.between(last, now).minus(threshold).isNegative
    }
}
