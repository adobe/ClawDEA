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

import org.junit.Assert.*
import org.junit.Test

class MessageRendererCostInfoTest {

    private val renderer = MessageRenderer()

    @Test
    fun `includes model effort time and cost`() {
        val html = renderer.renderCostInfo("claude-fable-5", "high", 0.0421, 12_300)
        assertTrue(html.contains("Fable 5"))
        assertTrue(html.contains("effort high"))
        // formatElapsed uses the JVM default locale; match locale-agnostic pattern "12<sep>3s"
        assertTrue(Regex("""12[.,]3s""").containsMatchIn(html))
        // cost also locale-sensitive; match locale-agnostic pattern
        assertTrue(Regex("""\$0[.,]0421""").containsMatchIn(html))
    }

    @Test
    fun `omits model and effort when blank or null`() {
        val html = renderer.renderCostInfo(null, null, 0.0, 5_000)
        assertFalse(html.contains("effort"))
        // formatElapsed uses the JVM default locale; match locale-agnostic pattern "5<sep>0s"
        assertTrue(Regex("""5[.,]0s""").containsMatchIn(html))
    }

    @Test
    fun `shows effort default when model present but effort blank`() {
        // Under the "Default" effort selection (blank flag), still surface the model
        // and "effort default" so the user sees what the turn ran with.
        val html = renderer.renderCostInfo("claude-opus-4-8", null, 0.0421, 0)
        assertTrue(html.contains("Opus 4 8"))
        assertTrue(html.contains("effort default"))
    }
}
