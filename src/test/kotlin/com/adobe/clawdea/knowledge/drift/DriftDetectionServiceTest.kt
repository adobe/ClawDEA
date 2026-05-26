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

import org.junit.Assert.assertEquals
import org.junit.Test

class DriftDetectionServiceTest {

    @Test fun `rescan bumps lastSyncedCommit to current HEAD when scan succeeds`() {
        // This test exercises the pure helper. The Git4Idea side is exercised by
        // the manual smoke test (./gradlew runIde + commit something).
        val before = DriftState(lastSyncedCommit = "")
        val after = DriftDetectionService.bumpSyncedCommit(before, headSha = "abc1234")
        assertEquals("abc1234", after.lastSyncedCommit)
    }

    @Test fun `bumpSyncedCommit no-ops when HEAD is blank`() {
        val before = DriftState(lastSyncedCommit = "old")
        val after = DriftDetectionService.bumpSyncedCommit(before, headSha = "")
        assertEquals("old", after.lastSyncedCommit)
    }

    @Test fun `bumpSyncedCommit overwrites previous SHA`() {
        val before = DriftState(lastSyncedCommit = "old")
        val after = DriftDetectionService.bumpSyncedCommit(before, headSha = "new1234")
        assertEquals("new1234", after.lastSyncedCommit)
    }

    @Test fun `shouldAdvanceSync is true on first run baseline`() {
        val before = DriftState(lastSyncedCommit = "")
        val applied = DriftDetectionService.Companion.ApplyResult(emptyList(), before)
        org.junit.Assert.assertTrue(DriftDetectionService.shouldAdvanceSync(before, applied))
    }

    @Test fun `shouldAdvanceSync is false when nothing was applied`() {
        val before = DriftState(lastSyncedCommit = "abc1234")
        val applied = DriftDetectionService.Companion.ApplyResult(emptyList(), before)
        org.junit.Assert.assertFalse(DriftDetectionService.shouldAdvanceSync(before, applied))
    }

    @Test fun `shouldAdvanceSync is true when events were applied`() {
        val before = DriftState(lastSyncedCommit = "abc1234")
        val event = DriftEvent.CodeRename(
            wikiPage = java.nio.file.Paths.get(".claude/wiki/concepts/x.md"),
            brokenLink = "old",
            suggestedReplacement = "new",
        )
        val applied = DriftDetectionService.Companion.ApplyResult(listOf(event), before)
        org.junit.Assert.assertTrue(DriftDetectionService.shouldAdvanceSync(before, applied))
    }

    @Test
    fun `applyAndDismiss with autoUpdate calls wiki-author for non-deterministic events`() = kotlinx.coroutines.runBlocking {
        val invoker = StubInvoker(actedOnAll = true)
        val events = listOf(
            DriftEvent.CommitDrift(
                wikiPage = java.nio.file.Paths.get(".claude/wiki/concepts/x.md"),
                commitShas = listOf("abc"),
                touchedPaths = listOf("src/main/kotlin/Foo.kt"),
                firstObservedAt = "2026-05-17T16:30:00Z",
            ),
        )
        val (remaining, applied) = DriftDetectionService.applyAndDismiss(
            events = events,
            autoUpdateEnabled = true,
            beforeState = DriftState(),
            today = "2026-05-17",
            wikiAuthorInvoker = invoker,
        )
        org.junit.Assert.assertTrue(remaining.isEmpty())
        org.junit.Assert.assertEquals(events, applied.events)
        org.junit.Assert.assertEquals(events.map { it.signature }, applied.newState.dismissed)
    }

    @Test
    fun `applyAndDismiss with autoUpdate skips wiki-author when only deterministic events`() = kotlinx.coroutines.runBlocking {
        var invokerCalled = false
        val invoker = StubInvoker { invokerCalled = true; emptySet() }
        val events = listOf(
            DriftEvent.CodeRename(
                wikiPage = java.nio.file.Paths.get(".claude/wiki/concepts/x.md"),
                brokenLink = "old",
                suggestedReplacement = "new",
            ),
        )
        DriftDetectionService.applyAndDismiss(
            events = events,
            autoUpdateEnabled = true,
            beforeState = DriftState(),
            today = "2026-05-17",
            wikiAuthorInvoker = invoker,
        )
        org.junit.Assert.assertFalse("invoker should not be called when only deterministic events", invokerCalled)
    }

    @Test
    fun `applyAndDismiss with autoUpdate off returns events untouched`() = kotlinx.coroutines.runBlocking {
        val events = listOf(
            DriftEvent.CommitDrift(
                wikiPage = java.nio.file.Paths.get(".claude/wiki/concepts/x.md"),
                commitShas = listOf("abc"),
                touchedPaths = listOf("src/main/kotlin/Foo.kt"),
                firstObservedAt = "2026-05-17T16:30:00Z",
            ),
        )
        val (remaining, applied) = DriftDetectionService.applyAndDismiss(
            events = events,
            autoUpdateEnabled = false,
            beforeState = DriftState(),
            today = "2026-05-17",
            wikiAuthorInvoker = StubInvoker(actedOnAll = false),
        )
        org.junit.Assert.assertEquals(events, remaining)
        org.junit.Assert.assertTrue(applied.events.isEmpty())
    }

    private class StubInvoker(
        private val actedOnAll: Boolean = false,
        private val actedOnFn: ((List<DriftEvent>) -> Set<String>)? = null,
    ) : WikiAuthorInvoker {
        constructor(fn: (List<DriftEvent>) -> Set<String>) : this(actedOnFn = fn)
        override suspend fun invoke(events: List<DriftEvent>): WikiAuthorInvoker.Result {
            val acted = actedOnFn?.invoke(events)
                ?: if (actedOnAll) events.map { it.signature }.toSet() else emptySet()
            val skipped = events.map { it.signature }.toSet() - acted
            return WikiAuthorInvoker.Result(acted, skipped, null)
        }
    }
}
