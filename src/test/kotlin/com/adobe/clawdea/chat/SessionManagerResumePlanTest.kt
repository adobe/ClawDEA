package com.adobe.clawdea.chat

import com.adobe.clawdea.chat.session.SessionOrigin
import com.adobe.clawdea.provider.BackendKind
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

    @Test
    fun `openai-compatible session with same profile resumes natively`() {
        assertTrue(
            SessionManager.resumeIsNative(
                SessionOrigin.OPENAI_COMPATIBLE,
                BackendKind.OPENAI_COMPATIBLE_HTTP,
                sessionProfileId = "profile-a",
                activeProfileId = "profile-a",
            )
        )
    }

    @Test
    fun `openai-compatible session with different profile is not native (replay)`() {
        assertFalse(
            SessionManager.resumeIsNative(
                SessionOrigin.OPENAI_COMPATIBLE,
                BackendKind.OPENAI_COMPATIBLE_HTTP,
                sessionProfileId = "profile-a",
                activeProfileId = "profile-b",
            )
        )
    }

    @Test
    fun `openai-compatible session on non-openai backend is not native (replay)`() {
        assertFalse(
            SessionManager.resumeIsNative(
                SessionOrigin.OPENAI_COMPATIBLE,
                BackendKind.CLAUDE_CLI,
                sessionProfileId = "profile-a",
                activeProfileId = "profile-a",
            )
        )
    }

    @Test
    fun `openai-compatible origin in legacy 2-arg overload returns false`() {
        // The old overload can't distinguish profiles, so cross-backend replay is safer
        assertFalse(SessionManager.resumeIsNative(SessionOrigin.OPENAI_COMPATIBLE, usesCodexBackend = false))
        assertFalse(SessionManager.resumeIsNative(SessionOrigin.OPENAI_COMPATIBLE, usesCodexBackend = true))
    }
}
