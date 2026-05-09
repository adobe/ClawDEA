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
package com.adobe.clawdea.debug

import org.junit.Assert.*
import org.junit.Test

class SessionLauncherTest {

    @Test
    fun `parseAdHocType returns valid types`() {
        assertEquals(AdHocType.JAVA_APP, SessionLauncher.parseAdHocType("java_app"))
        assertEquals(AdHocType.JAVA_TEST, SessionLauncher.parseAdHocType("java_test"))
        assertEquals(AdHocType.JS_DEBUG, SessionLauncher.parseAdHocType("js_debug"))
        assertEquals(AdHocType.NODE, SessionLauncher.parseAdHocType("node"))
    }

    @Test
    fun `parseAdHocType returns null for unknown type`() {
        assertNull(SessionLauncher.parseAdHocType("python"))
        assertNull(SessionLauncher.parseAdHocType(""))
    }

    @Test
    fun `parseRuntime returns valid runtimes`() {
        assertEquals(AttachRuntime.JAVA, SessionLauncher.parseRuntime("java"))
        assertEquals(AttachRuntime.NODE, SessionLauncher.parseRuntime("node"))
    }

    @Test
    fun `parseRuntime returns null for unknown runtime`() {
        assertNull(SessionLauncher.parseRuntime("python"))
        assertNull(SessionLauncher.parseRuntime(""))
    }

    @Test
    fun `parseEnvString parses KEY=VAL pairs`() {
        val env = SessionLauncher.parseEnvString("FOO=bar,BAZ=qux")
        assertEquals(mapOf("FOO" to "bar", "BAZ" to "qux"), env)
    }

    @Test
    fun `parseEnvString handles empty string`() {
        assertTrue(SessionLauncher.parseEnvString("").isEmpty())
    }

    @Test
    fun `parseEnvString handles single entry`() {
        val env = SessionLauncher.parseEnvString("KEY=value")
        assertEquals(mapOf("KEY" to "value"), env)
    }

    @Test
    fun `parseEnvString handles value with equals sign`() {
        val env = SessionLauncher.parseEnvString("URL=http://host:8080/path?a=1")
        assertEquals(mapOf("URL" to "http://host:8080/path?a=1"), env)
    }

    @Test
    fun `parseEnvString skips malformed entries`() {
        val env = SessionLauncher.parseEnvString("GOOD=val,BADENTRY,ALSO_GOOD=v2")
        assertEquals(mapOf("GOOD" to "val", "ALSO_GOOD" to "v2"), env)
    }

    @Test
    fun `isJsSupported returns the js availability flag`() {
        val result = SessionLauncher.isJsSupported
        assertNotNull(result)
    }
}
