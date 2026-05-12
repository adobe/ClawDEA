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
package com.adobe.clawdea.knowledge.corrections

import org.junit.Assert.*
import org.junit.Test

class CorrectionDetectorTest {

    private val prior = "I think the policy is bound at page/v3/page."

    @Test fun `detects no that's wrong with reason`() {
        val signal = CorrectionDetector.detect("No, that's wrong — the policy is inert because v3 templates don't bind it.", prior)
        assertNotNull(signal)
    }

    @Test fun `detects actually correction`() {
        val signal = CorrectionDetector.detect("Actually the resolution happens in the template, not the policy.", prior)
        assertNotNull(signal)
    }

    @Test fun `detects missed correction`() {
        val signal = CorrectionDetector.detect("You missed the clientlib dependency graph for v2 pages.", prior)
        assertNotNull(signal)
    }

    @Test fun `ignores short affirmatives`() {
        assertNull(CorrectionDetector.detect("yes", prior))
        assertNull(CorrectionDetector.detect("ok", prior))
    }

    @Test fun `ignores 'yes continue' which lacks correction keyword`() {
        assertNull(CorrectionDetector.detect("yes, continue", prior))
    }

    @Test fun `returns null when prior assistant is blank`() {
        assertNull(CorrectionDetector.detect("No, that's wrong.", ""))
    }

    @Test fun `returns null for very short user message`() {
        assertNull(CorrectionDetector.detect("no", prior))
    }

    @Test fun `draftTopic extracts first 5 non-stopword tokens`() {
        val topic = CorrectionDetector.draftTopic("No, that's wrong — the policy is inert because v3 templates don't bind it.")
        val parts = topic.split("-")
        assertTrue(parts.size <= 5)
        assertTrue(parts.all { it !in setOf("the", "a", "is", "no", "not") })
    }

    @Test fun `draftTopic returns 'correction' fallback on empty input`() {
        assertEquals("correction", CorrectionDetector.draftTopic(""))
    }
}
