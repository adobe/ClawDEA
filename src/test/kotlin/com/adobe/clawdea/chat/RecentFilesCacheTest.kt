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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recently-modified-files list feeds `@`-mention autocomplete, which runs on the EDT on every
 * keystroke. Forking `git log` there froze the UI, so the list is cached and refreshed off-EDT
 * behind a TTL. These are the cache's two pure decisions.
 */
class RecentFilesCacheTest {

    @Test
    fun `a never-refreshed cache is stale`() {
        assertTrue(RecentFilesCache.isStale(lastRefreshAtMs = 0L, nowMs = 1L, ttlMs = 30_000L))
    }

    @Test
    fun `a fresh cache is not stale`() {
        assertFalse(RecentFilesCache.isStale(lastRefreshAtMs = 1_000L, nowMs = 5_000L, ttlMs = 30_000L))
    }

    @Test
    fun `a cache past its ttl is stale`() {
        assertTrue(RecentFilesCache.isStale(lastRefreshAtMs = 1_000L, nowMs = 31_001L, ttlMs = 30_000L))
    }

    @Test
    fun `ttl boundary counts as stale`() {
        assertTrue(RecentFilesCache.isStale(lastRefreshAtMs = 1_000L, nowMs = 31_000L, ttlMs = 30_000L))
    }

    @Test
    fun `a clock that went backwards does not wedge the cache`() {
        assertTrue(RecentFilesCache.isStale(lastRefreshAtMs = 10_000L, nowMs = 500L, ttlMs = 30_000L))
    }

    @Test
    fun `parseGitLog dedupes preserving first-seen order and drops blanks`() {
        val output = """
            src/a.kt

            src/b.kt
            src/a.kt

            src/c.kt
        """.trimIndent()

        assertEquals(listOf("src/a.kt", "src/b.kt", "src/c.kt"), RecentFilesCache.parseGitLog(output, max = 10))
    }

    @Test
    fun `parseGitLog honors max`() {
        val output = (1..50).joinToString("\n") { "src/f$it.kt" }

        assertEquals(listOf("src/f1.kt", "src/f2.kt", "src/f3.kt"), RecentFilesCache.parseGitLog(output, max = 3))
    }

    @Test
    fun `parseGitLog on empty output returns nothing`() {
        assertTrue(RecentFilesCache.parseGitLog("", max = 10).isEmpty())
        assertTrue(RecentFilesCache.parseGitLog("\n\n  \n", max = 10).isEmpty())
    }
}
