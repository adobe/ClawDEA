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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Test
    fun `cleanup does not throw while other threads mutate concurrently`() {
        val tracker = BreakpointTracker()
        val threads = 4
        val perThread = 500
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads + 1)

        val mutators = (0 until threads).map { threadIndex ->
            Thread {
                try {
                    start.await()
                    for (i in 0 until perThread) {
                        val id = BreakpointId("File$threadIndex.kt", i)
                        tracker.trackClaudeBreakpoint(id)
                        tracker.trackDisabledUserBreakpoint(id)
                        tracker.trackBorrowedBreakpoint(id, wasDisabled = i % 2 == 0)
                        tracker.isClaudeOwned(id)
                        tracker.isBorrowed(id)
                        tracker.disabledUserBreakpoints
                        tracker.untrackBorrowedBreakpoint(id)
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }
        }
        val cleaner = Thread {
            try {
                start.await()
                repeat(200) { tracker.cleanup() }
            } catch (e: Throwable) {
                errors.add(e)
            } finally {
                done.countDown()
            }
        }

        (mutators + cleaner).forEach { it.start() }
        start.countDown()
        assertTrue("threads did not finish in 30s", done.await(30, TimeUnit.SECONDS))
        assertTrue("concurrent access threw: ${errors.map { it.toString() }}", errors.isEmpty())
    }

    @Test
    fun `cleanup empties every set so a later cleanup reports nothing`() {
        val tracker = BreakpointTracker()
        val id = BreakpointId("A.kt", 10)
        tracker.trackClaudeBreakpoint(id)
        tracker.trackDisabledUserBreakpoint(id)
        tracker.trackBorrowedBreakpoint(id, wasDisabled = true)

        val first = tracker.cleanup()
        assertEquals(setOf(id), first.claudeBreakpointsToRemove)
        assertEquals(setOf(id), first.userBreakpointsToReEnable)
        assertEquals(setOf(id), first.borrowedToReDisable)

        val second = tracker.cleanup()
        assertTrue(second.claudeBreakpointsToRemove.isEmpty())
        assertTrue(second.userBreakpointsToReEnable.isEmpty())
        assertTrue(second.borrowedToReDisable.isEmpty())
        assertFalse(tracker.isClaudeOwned(id))
        assertFalse(tracker.isBorrowed(id))
    }

    @Test
    fun `concurrent adds and cleanups preserve conservation - every id is reported exactly once`() {
        val tracker = BreakpointTracker()
        val threads = 4
        val perThread = 500
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads + 1)
        val allCleanupResults = ConcurrentLinkedQueue<CleanupResult>()

        val adders = (0 until threads).map { threadIndex ->
            Thread {
                try {
                    start.await()
                    for (i in 0 until perThread) {
                        val id = BreakpointId("File$threadIndex.kt", i)
                        tracker.trackClaudeBreakpoint(id)
                        tracker.trackDisabledUserBreakpoint(id)
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }
        }
        val cleaner = Thread {
            try {
                start.await()
                repeat(50) {
                    allCleanupResults.add(tracker.cleanup())
                    Thread.sleep(1)
                }
            } catch (e: Throwable) {
                errors.add(e)
            } finally {
                done.countDown()
            }
        }

        (adders + cleaner).forEach { it.start() }
        start.countDown()
        assertTrue("threads did not finish in 30s", done.await(30, TimeUnit.SECONDS))
        assertTrue("concurrent access threw: ${errors.map { it.toString() }}", errors.isEmpty())

        // Conservation: every id tracked is reported by exactly one cleanup (or still in tracker at end)
        val allTrackedIds = (0 until threads).flatMap { t ->
            (0 until perThread).map { i -> BreakpointId("File$t.kt", i) }
        }.toSet()

        val reportedClaudeIds = allCleanupResults.flatMap { it.claudeBreakpointsToRemove }.toSet()
        val reportedDisabledIds = allCleanupResults.flatMap { it.userBreakpointsToReEnable }.toSet()

        // Final cleanup to capture anything still in tracker
        val finalCleanup = tracker.cleanup()
        val finalClaudeIds = reportedClaudeIds + finalCleanup.claudeBreakpointsToRemove
        val finalDisabledIds = reportedDisabledIds + finalCleanup.userBreakpointsToReEnable

        assertEquals("Every tracked Claude breakpoint must appear in cleanup results exactly once",
            allTrackedIds, finalClaudeIds)
        assertEquals("Every tracked disabled breakpoint must appear in cleanup results exactly once",
            allTrackedIds, finalDisabledIds)

        // Verify no id appeared in multiple cleanup calls (no double-reporting)
        val allClaudeReports = allCleanupResults.flatMap { it.claudeBreakpointsToRemove } + finalCleanup.claudeBreakpointsToRemove
        val allDisabledReports = allCleanupResults.flatMap { it.userBreakpointsToReEnable } + finalCleanup.userBreakpointsToReEnable
        assertEquals("Claude breakpoints must not be reported twice", allClaudeReports.size, allClaudeReports.toSet().size)
        assertEquals("Disabled breakpoints must not be reported twice", allDisabledReports.size, allDisabledReports.toSet().size)
    }
}
