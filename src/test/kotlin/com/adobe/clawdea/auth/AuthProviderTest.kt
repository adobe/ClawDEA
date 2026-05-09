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

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertEquals

class AuthProviderTest {
    @Test
    fun `valid AuthValidation has no message`() {
        val v = AuthValidation(valid = true, message = null)
        assertTrue(v.valid)
        assertNull(v.message)
    }

    @Test
    fun `invalid AuthValidation has message`() {
        val v = AuthValidation(valid = false, message = "No API key")
        assertFalse(v.valid)
        assertEquals("No API key", v.message)
    }
}
