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

import java.util.concurrent.ConcurrentHashMap

data class BreakpointId(val file: String, val line: Int)

data class CleanupResult(
    val claudeBreakpointsToRemove: Set<BreakpointId>,
    val userBreakpointsToReEnable: Set<BreakpointId>,
    val borrowedToReDisable: Set<BreakpointId>,
)

/**
 * Tracks which breakpoints the debug tools own, borrowed, or disabled.
 *
 * Thread safety: mutated from MCP dispatch threads (one per concurrent tool call), the EDT
 * (XBreakpointListener callbacks in DebugBridge), and debugger event threads. The backing sets are
 * concurrent so reads never throw, and all mutations (add/remove/borrow bookkeeping/cleanup's
 * snapshot-then-clear) run under [lock] so [cleanup] snapshots an instant in time — an add that
 * interleaves with cleanup is either fully captured in the snapshot or happens entirely after it,
 * never half-seen. Without this, an id added between toSet() and clear() would be silently dropped
 * (reported in no cleanup result yet erased from the tracker), leaking Claude's breakpoints into
 * the user's project or leaving user breakpoints disabled forever.
 */
class BreakpointTracker {

    private val lock = Any()

    private val claudeBreakpoints: MutableSet<BreakpointId> = ConcurrentHashMap.newKeySet()
    private val _disabledUserBreakpoints: MutableSet<BreakpointId> = ConcurrentHashMap.newKeySet()
    private val _borrowedUserBreakpoints: MutableSet<BreakpointId> = ConcurrentHashMap.newKeySet()
    private val _borrowedWasDisabled: MutableSet<BreakpointId> = ConcurrentHashMap.newKeySet()

    val disabledUserBreakpoints: Set<BreakpointId>
        get() = _disabledUserBreakpoints.toSet()

    fun trackClaudeBreakpoint(id: BreakpointId) {
        synchronized(lock) {
            claudeBreakpoints.add(id)
        }
    }

    fun untrackClaudeBreakpoint(id: BreakpointId) {
        synchronized(lock) {
            claudeBreakpoints.remove(id)
        }
    }

    fun isClaudeOwned(id: BreakpointId): Boolean = id in claudeBreakpoints

    fun isBorrowed(id: BreakpointId): Boolean = id in _borrowedUserBreakpoints

    fun trackBorrowedBreakpoint(id: BreakpointId, wasDisabled: Boolean) {
        synchronized(lock) {
            _borrowedUserBreakpoints.add(id)
            if (wasDisabled) _borrowedWasDisabled.add(id)
        }
    }

    /** Returns true when the borrowed breakpoint had been disabled before we borrowed it. */
    fun untrackBorrowedBreakpoint(id: BreakpointId): Boolean = synchronized(lock) {
        val wasBorrowed = _borrowedUserBreakpoints.remove(id)
        val wasDisabled = _borrowedWasDisabled.remove(id)
        wasBorrowed && wasDisabled
    }

    fun trackDisabledUserBreakpoint(id: BreakpointId) {
        synchronized(lock) {
            _disabledUserBreakpoints.add(id)
        }
    }

    fun untrackDisabledUserBreakpoint(id: BreakpointId) {
        synchronized(lock) {
            _disabledUserBreakpoints.remove(id)
        }
    }

    fun cleanup(): CleanupResult = synchronized(lock) {
        val result = CleanupResult(
            claudeBreakpointsToRemove = claudeBreakpoints.toSet(),
            userBreakpointsToReEnable = _disabledUserBreakpoints.toSet(),
            borrowedToReDisable = _borrowedWasDisabled.toSet(),
        )
        claudeBreakpoints.clear()
        _disabledUserBreakpoints.clear()
        _borrowedUserBreakpoints.clear()
        _borrowedWasDisabled.clear()
        result
    }
}
