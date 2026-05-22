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
package com.adobe.clawdea.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class FilesystemRefreshCoordinatorTest {

    private class FakeDebounceScheduler : DebounceScheduler {
        private data class Pending(val delayMs: Long, val task: () -> Unit)
        private var pending: Pending? = null
        private var virtualClockMs: Long = 0
        var scheduledCount: Int = 0

        override fun schedule(delayMs: Long, task: () -> Unit) {
            scheduledCount++
            pending = Pending(virtualClockMs + delayMs, task)
        }

        override fun cancelAll() {
            pending = null
        }

        fun advanceTimeBy(ms: Long) {
            virtualClockMs += ms
            val p = pending
            if (p != null && virtualClockMs >= p.delayMs) {
                pending = null
                p.task()
            }
        }
    }

    private class FakeRefreshOperations : RefreshOperations {
        var broadCount: Int = 0
        val fileCalls: MutableList<String> = mutableListOf()

        override fun refreshBroad() {
            broadCount++
        }

        override fun refreshFile(filePath: String) {
            fileCalls.add(filePath)
        }
    }

    @Test
    fun `onBashCompleted runs broad refresh once after the debounce window elapses`() {
        val ops = FakeRefreshOperations()
        val scheduler = FakeDebounceScheduler()
        val coordinator = FilesystemRefreshCoordinator(ops, scheduler)

        coordinator.onBashCompleted()
        assertEquals(0, ops.broadCount)

        scheduler.advanceTimeBy(FilesystemRefreshCoordinator.BASH_DEBOUNCE_MS)
        assertEquals(1, ops.broadCount)
    }

    @Test
    fun `three onBashCompleted calls within the window coalesce into one broad refresh`() {
        val ops = FakeRefreshOperations()
        val scheduler = FakeDebounceScheduler()
        val coordinator = FilesystemRefreshCoordinator(ops, scheduler)

        coordinator.onBashCompleted()
        scheduler.advanceTimeBy(200)
        coordinator.onBashCompleted()
        scheduler.advanceTimeBy(200)
        coordinator.onBashCompleted()

        scheduler.advanceTimeBy(FilesystemRefreshCoordinator.BASH_DEBOUNCE_MS)

        assertEquals(1, ops.broadCount)
    }

    @Test
    fun `onBashCompleted after a completed refresh schedules a second refresh`() {
        val ops = FakeRefreshOperations()
        val scheduler = FakeDebounceScheduler()
        val coordinator = FilesystemRefreshCoordinator(ops, scheduler)

        coordinator.onBashCompleted()
        scheduler.advanceTimeBy(FilesystemRefreshCoordinator.BASH_DEBOUNCE_MS)
        assertEquals(1, ops.broadCount)

        coordinator.onBashCompleted()
        scheduler.advanceTimeBy(FilesystemRefreshCoordinator.BASH_DEBOUNCE_MS)
        assertEquals(2, ops.broadCount)
    }

    @Test
    fun `onEditApplied calls refreshFile immediately and does not schedule`() {
        val ops = FakeRefreshOperations()
        val scheduler = FakeDebounceScheduler()
        val coordinator = FilesystemRefreshCoordinator(ops, scheduler)

        coordinator.onEditApplied("/tmp/a.kt")

        assertEquals(listOf("/tmp/a.kt"), ops.fileCalls)
        assertEquals(0, ops.broadCount)
    }

    @Test
    fun `onMassFileChange triggers immediate broad refresh without debounce`() {
        val ops = FakeRefreshOperations()
        val scheduler = FakeDebounceScheduler()
        val coordinator = FilesystemRefreshCoordinator(ops, scheduler)

        coordinator.onMassFileChange()

        assertEquals(1, ops.broadCount)
        assertEquals(0, scheduler.scheduledCount)

        scheduler.advanceTimeBy(FilesystemRefreshCoordinator.BASH_DEBOUNCE_MS * 2)
        assertEquals(1, ops.broadCount)
    }

    @Test
    fun `onBashCompleted and onEditApplied do not interfere with each other`() {
        val ops = FakeRefreshOperations()
        val scheduler = FakeDebounceScheduler()
        val coordinator = FilesystemRefreshCoordinator(ops, scheduler)

        coordinator.onBashCompleted()
        coordinator.onEditApplied("/tmp/a.kt")

        assertEquals(listOf("/tmp/a.kt"), ops.fileCalls)
        assertEquals(0, ops.broadCount)

        scheduler.advanceTimeBy(FilesystemRefreshCoordinator.BASH_DEBOUNCE_MS)
        assertEquals(1, ops.broadCount)
    }
}
