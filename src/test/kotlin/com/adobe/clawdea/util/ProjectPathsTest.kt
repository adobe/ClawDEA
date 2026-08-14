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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProjectPathsTest {

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    @Test
    fun `relative paths are not absolute on any host`() {
        assertFalse(ProjectPaths.isAbsolutePath("src/main/Foo.kt"))
        assertFalse(ProjectPaths.isAbsolutePath("Foo.kt"))
    }

    @Test
    fun `a POSIX leading-slash path is absolute on POSIX`() {
        assumeFalse(isWindows)
        assertTrue(ProjectPaths.isAbsolutePath("/proj/Foo.kt"))
    }

    @Test
    fun `a drive-letter path is absolute on Windows (the bug startsWith slash missed)`() {
        assumeTrue(isWindows)
        assertTrue(ProjectPaths.isAbsolutePath("C:\\proj\\Foo.kt"))
    }
}
