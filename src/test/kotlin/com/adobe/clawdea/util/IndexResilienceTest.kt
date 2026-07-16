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
package com.adobe.clawdea.util

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class IndexResilienceTest {

    @Test fun `returns block result when no exception`() {
        assertEquals("ok", ignoringStaleIndex { "ok" })
    }

    @Test fun `returns null and reports skip when a stale-index exception is thrown`() {
        var skipped: Exception? = null
        val result = ignoringStaleIndex<String>(onSkip = { skipped = it }) {
            // Mimics StubTreeAndIndexUnmatchException surfacing through getTextOffset().
            throw IllegalStateException("Outdated stub in index")
        }
        assertNull(result)
        assertEquals("Outdated stub in index", skipped?.message)
    }

    @Test fun `rethrows ProcessCanceledException`() {
        try {
            ignoringStaleIndex<String> { throw ProcessCanceledException() }
            fail("Expected ProcessCanceledException to propagate")
        } catch (_: ProcessCanceledException) {
            // expected — PCE must never be swallowed
        }
    }

    @Test fun `does not invoke onSkip on ProcessCanceledException`() {
        var skipCalled = false
        try {
            ignoringStaleIndex<String>(onSkip = { skipCalled = true }) {
                throw ProcessCanceledException()
            }
        } catch (_: ProcessCanceledException) {
            // expected
        }
        assertEquals(false, skipCalled)
    }
}
