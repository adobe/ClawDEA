package com.adobe.clawdea.chat

import com.adobe.clawdea.chat.session.SessionOrigin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerResumePlanTest {

    @Test
    fun `claude session on claude backend resumes natively`() {
        assertTrue(SessionManager.resumeIsNative(SessionOrigin.CLAUDE, usesCodexBackend = false))
    }

    @Test
    fun `codex session on codex backend resumes natively`() {
        assertTrue(SessionManager.resumeIsNative(SessionOrigin.CODEX, usesCodexBackend = true))
    }

    @Test
    fun `claude session on codex backend is not native (replay)`() {
        assertFalse(SessionManager.resumeIsNative(SessionOrigin.CLAUDE, usesCodexBackend = true))
    }

    @Test
    fun `codex session on claude backend is not native (replay)`() {
        assertFalse(SessionManager.resumeIsNative(SessionOrigin.CODEX, usesCodexBackend = false))
    }
}
