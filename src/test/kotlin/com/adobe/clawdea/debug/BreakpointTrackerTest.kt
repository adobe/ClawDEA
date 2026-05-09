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

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BreakpointTrackerTest {

    private lateinit var tracker: BreakpointTracker

    @Before
    fun setUp() {
        tracker = BreakpointTracker()
    }

    @Test
    fun `trackClaudeBreakpoint adds to set`() {
        val id = BreakpointId("Foo.kt", 10)
        tracker.trackClaudeBreakpoint(id)
        assertTrue(tracker.isClaudeOwned(id))
    }

    @Test
    fun `isClaudeOwned returns false for unknown breakpoint`() {
        assertFalse(tracker.isClaudeOwned(BreakpointId("Foo.kt", 10)))
    }

    @Test
    fun `untrackClaudeBreakpoint removes from set`() {
        val id = BreakpointId("Foo.kt", 10)
        tracker.trackClaudeBreakpoint(id)
        tracker.untrackClaudeBreakpoint(id)
        assertFalse(tracker.isClaudeOwned(id))
    }

    @Test
    fun `trackDisabledUserBreakpoint records the breakpoint`() {
        val id = BreakpointId("Bar.kt", 20)
        tracker.trackDisabledUserBreakpoint(id)
        assertTrue(tracker.disabledUserBreakpoints.contains(id))
    }

    @Test
    fun `untrackDisabledUserBreakpoint removes from set`() {
        val id = BreakpointId("Bar.kt", 20)
        tracker.trackDisabledUserBreakpoint(id)
        tracker.untrackDisabledUserBreakpoint(id)
        assertFalse(tracker.disabledUserBreakpoints.contains(id))
    }

    @Test
    fun `trackBorrowedBreakpoint marks as borrowed`() {
        val id = BreakpointId("Foo.kt", 10)
        tracker.trackBorrowedBreakpoint(id, wasDisabled = false)
        assertTrue(tracker.isBorrowed(id))
        assertFalse(tracker.isClaudeOwned(id))
    }

    @Test
    fun `untrackBorrowedBreakpoint returns wasDisabled flag`() {
        val enabled = BreakpointId("A.kt", 1)
        val disabled = BreakpointId("B.kt", 2)
        tracker.trackBorrowedBreakpoint(enabled, wasDisabled = false)
        tracker.trackBorrowedBreakpoint(disabled, wasDisabled = true)

        assertFalse(tracker.untrackBorrowedBreakpoint(enabled))
        assertTrue(tracker.untrackBorrowedBreakpoint(disabled))
        assertFalse(tracker.isBorrowed(enabled))
        assertFalse(tracker.isBorrowed(disabled))
    }

    @Test
    fun `cleanup returns all tracked ids and clears state`() {
        val claude1 = BreakpointId("A.kt", 1)
        val claude2 = BreakpointId("B.kt", 2)
        val disabled1 = BreakpointId("C.kt", 3)
        val borrowed1 = BreakpointId("D.kt", 4)
        val borrowed2 = BreakpointId("E.kt", 5)
        tracker.trackClaudeBreakpoint(claude1)
        tracker.trackClaudeBreakpoint(claude2)
        tracker.trackDisabledUserBreakpoint(disabled1)
        tracker.trackBorrowedBreakpoint(borrowed1, wasDisabled = true)
        tracker.trackBorrowedBreakpoint(borrowed2, wasDisabled = false)

        val result = tracker.cleanup()
        assertEquals(setOf(claude1, claude2), result.claudeBreakpointsToRemove)
        assertEquals(setOf(disabled1), result.userBreakpointsToReEnable)
        assertEquals(setOf(borrowed1), result.borrowedToReDisable)

        // State is cleared
        assertFalse(tracker.isClaudeOwned(claude1))
        assertFalse(tracker.isBorrowed(borrowed1))
        assertTrue(tracker.disabledUserBreakpoints.isEmpty())
    }

    @Test
    fun `cleanup on empty tracker returns empty sets`() {
        val result = tracker.cleanup()
        assertTrue(result.claudeBreakpointsToRemove.isEmpty())
        assertTrue(result.userBreakpointsToReEnable.isEmpty())
        assertTrue(result.borrowedToReDisable.isEmpty())
    }
}
