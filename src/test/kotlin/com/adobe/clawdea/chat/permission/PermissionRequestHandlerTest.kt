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
package com.adobe.clawdea.chat.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for the late-answer user-message bodies produced by
 * [PermissionRequestHandler]. The handler itself is wired to JCEF and
 * exercised end-to-end in the IDE; here we just lock down the message
 * shape Claude actually sees on the next turn.
 */
class PermissionRequestHandlerTest {

    @Test
    fun `late answer message lists every non-blank answer with a Q to A bullet`() {
        val msg = PermissionRequestHandler.buildLateAnswerMessage(
            linkedMapOf(
                "Which approach?" to "Option A",
                "Which library?" to "Lib X, Lib Y",
            ),
        )
        assertTrue("expected first answer line, got: $msg", msg.contains("- Which approach? → Option A"))
        assertTrue("expected multi-select answer line, got: $msg", msg.contains("- Which library? → Lib X, Lib Y"))
    }

    @Test
    fun `late answer message acknowledges the timeout so Claude does not re-issue the tool call`() {
        val msg = PermissionRequestHandler.buildLateAnswerMessage(
            mapOf("q" to "a"),
        )
        // Claude is told this is a follow-up because the original tool
        // round-trip already finalised — that's what stops it from
        // re-asking via AskUserQuestion when the user previously typed
        // "continue".
        assertTrue("expected timeout context, got: $msg", msg.contains("timed out"))
        assertTrue("expected follow-up framing, got: $msg", msg.contains("follow-up"))
        assertTrue("expected continue-from-here directive, got: $msg", msg.contains("continue from here"))
    }

    @Test
    fun `late answer message skips blank questions and labels`() {
        val msg = PermissionRequestHandler.buildLateAnswerMessage(
            mapOf(
                "" to "ignored",
                "real" to "",
                "kept" to "yes",
            ),
        )
        assertFalse("blank-key answer must not appear: $msg", msg.contains("ignored"))
        assertFalse("blank-value answer must not appear: $msg", msg.contains("- real "))
        assertTrue(msg.contains("- kept → yes"))
    }

    @Test
    fun `late skip message tells Claude to proceed without the input`() {
        val msg = PermissionRequestHandler.LATE_SKIP_MESSAGE
        assertTrue("expected skip context: $msg", msg.contains("skipped"))
        assertTrue("expected proceed-without-input directive: $msg", msg.contains("Please continue without"))
    }
}
