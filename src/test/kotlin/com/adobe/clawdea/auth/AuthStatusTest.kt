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
package com.adobe.clawdea.auth

import org.junit.Assert.*
import org.junit.Test

class AuthStatusTest {

    @Test
    fun `NotSignedIn is a singleton object`() {
        val a: AuthStatus = AuthStatus.NotSignedIn
        val b: AuthStatus = AuthStatus.NotSignedIn
        assertSame(a, b)
    }

    @Test
    fun `SignedIn holds nullable tier and email`() {
        val s = AuthStatus.SignedIn(tier = "pro", email = "user@example.com")
        assertEquals("pro", s.tier)
        assertEquals("user@example.com", s.email)

        val bare = AuthStatus.SignedIn(tier = null, email = null)
        assertNull(bare.tier)
        assertNull(bare.email)
    }

    @Test
    fun `Invalid carries a reason`() {
        val i = AuthStatus.Invalid("token expired")
        assertEquals("token expired", i.reason)
    }

    @Test
    fun `Unknown is a singleton object`() {
        val a: AuthStatus = AuthStatus.Unknown
        val b: AuthStatus = AuthStatus.Unknown
        assertSame(a, b)
    }

    @Test
    fun `isSignedIn helper returns true only for SignedIn`() {
        assertTrue(AuthStatus.SignedIn(null, null).isSignedIn())
        assertFalse(AuthStatus.NotSignedIn.isSignedIn())
        assertFalse(AuthStatus.Invalid("x").isSignedIn())
        assertFalse(AuthStatus.Unknown.isSignedIn())
    }
}
