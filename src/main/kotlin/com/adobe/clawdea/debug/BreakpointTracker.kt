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

data class BreakpointId(val file: String, val line: Int)

data class CleanupResult(
    val claudeBreakpointsToRemove: Set<BreakpointId>,
    val userBreakpointsToReEnable: Set<BreakpointId>,
    val borrowedToReDisable: Set<BreakpointId>,
)

class BreakpointTracker {

    private val claudeBreakpoints = mutableSetOf<BreakpointId>()
    private val _disabledUserBreakpoints = mutableSetOf<BreakpointId>()
    private val _borrowedUserBreakpoints = mutableSetOf<BreakpointId>()
    private val _borrowedWasDisabled = mutableSetOf<BreakpointId>()

    val disabledUserBreakpoints: Set<BreakpointId>
        get() = _disabledUserBreakpoints.toSet()

    fun trackClaudeBreakpoint(id: BreakpointId) {
        claudeBreakpoints.add(id)
    }

    fun untrackClaudeBreakpoint(id: BreakpointId) {
        claudeBreakpoints.remove(id)
    }

    fun isClaudeOwned(id: BreakpointId): Boolean = id in claudeBreakpoints

    fun isBorrowed(id: BreakpointId): Boolean = id in _borrowedUserBreakpoints

    fun trackBorrowedBreakpoint(id: BreakpointId, wasDisabled: Boolean) {
        _borrowedUserBreakpoints.add(id)
        if (wasDisabled) _borrowedWasDisabled.add(id)
    }

    fun untrackBorrowedBreakpoint(id: BreakpointId): Boolean {
        _borrowedUserBreakpoints.remove(id)
        return _borrowedWasDisabled.remove(id)
    }

    fun trackDisabledUserBreakpoint(id: BreakpointId) {
        _disabledUserBreakpoints.add(id)
    }

    fun untrackDisabledUserBreakpoint(id: BreakpointId) {
        _disabledUserBreakpoints.remove(id)
    }

    fun cleanup(): CleanupResult {
        val result = CleanupResult(
            claudeBreakpointsToRemove = claudeBreakpoints.toSet(),
            userBreakpointsToReEnable = _disabledUserBreakpoints.toSet(),
            borrowedToReDisable = _borrowedWasDisabled.toSet(),
        )
        claudeBreakpoints.clear()
        _disabledUserBreakpoints.clear()
        _borrowedUserBreakpoints.clear()
        _borrowedWasDisabled.clear()
        return result
    }
}
