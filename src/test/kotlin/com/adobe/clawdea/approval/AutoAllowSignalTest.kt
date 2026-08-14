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
package com.adobe.clawdea.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAllowSignalTest {

    @Test fun `consume returns false when nothing was notified`() {
        val signal = AutoAllowSignal()
        assertFalse(signal.consume("Bash", """{"command":"ls"}"""))
    }

    @Test fun `consume returns true exactly once for the matching pair`() {
        val signal = AutoAllowSignal()
        signal.notify("Bash", """{"command":"ls"}""")
        assertTrue("first consume must match", signal.consume("Bash", """{"command":"ls"}"""))
        assertFalse(
            "second consume on the same pair must miss — guards against double-rendering the marker",
            signal.consume("Bash", """{"command":"ls"}"""),
        )
    }

    @Test fun `each tool call carries its own input — same tool name with different input does not match`() {
        // Cache key is (toolName, inputJson). Two Bash calls with different
        // commands must not share a signal — otherwise an auto-allow on `ls`
        // could mark the next tab's `rm -rf` block as auto-allowed.
        val signal = AutoAllowSignal()
        signal.notify("Bash", """{"command":"ls"}""")
        assertFalse(signal.consume("Bash", """{"command":"rm -rf /"}"""))
        assertTrue("the original entry must still be available", signal.consume("Bash", """{"command":"ls"}"""))
    }

    @Test fun `multiple distinct notifications are tracked independently`() {
        val signal = AutoAllowSignal()
        signal.notify("Bash", """{"command":"ls"}""")
        signal.notify("Bash", """{"command":"pwd"}""")
        assertEquals(2, signal.pendingSize())
        assertTrue(signal.consume("Bash", """{"command":"pwd"}"""))
        assertEquals(1, signal.pendingSize())
        assertTrue(signal.consume("Bash", """{"command":"ls"}"""))
        assertEquals(0, signal.pendingSize())
    }

    @Test fun `id-based consume matches by tool_use_id regardless of input bytes`() {
        // Regression: stream-json and JSON-RPC may serialize the same input with
        // different whitespace/escaping. The id is the byte-stable handle, so a
        // notify-by-id must be consumable by the same id even when the
        // (toolName, inputJson) the consumer would have used has drifted.
        val signal = AutoAllowSignal()
        signal.notify("Bash", """{ "command":"ls" }""", toolUseId = "tu-1")
        assertTrue(signal.consume("tu-1"))
        assertEquals(0, signal.pendingSize())
    }

    @Test fun `id consume returns false for empty id and for unknown id`() {
        val signal = AutoAllowSignal()
        signal.notify("Bash", """{"command":"ls"}""", toolUseId = "tu-1")
        assertFalse(signal.consume(""))
        assertFalse(signal.consume("tu-other"))
        assertTrue("the original entry must still be available", signal.consume("tu-1"))
    }

    @Test fun `notify with id and consume by name+input do not cross-match`() {
        // The two keying spaces are kept distinct so an id-keyed notify
        // doesn't accidentally satisfy a name-keyed consume in a different
        // panel (and vice versa).
        val signal = AutoAllowSignal()
        signal.notify("Bash", """{"command":"ls"}""", toolUseId = "tu-1")
        assertFalse(signal.consume("Bash", """{"command":"ls"}"""))
        assertTrue(signal.consume("tu-1"))
    }
}
