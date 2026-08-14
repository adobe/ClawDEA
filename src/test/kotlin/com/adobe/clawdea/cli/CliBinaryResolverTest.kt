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
package com.adobe.clawdea.cli

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

class CliBinaryResolverTest {

    // A binary name guaranteed absent from every well-known install dir, so the probe misses and the
    // shell-fallback / bare-name branch is what's under test (not whatever is really installed here).
    private val fake = "clawdea-zzz-absent-cli"

    @Before
    fun posixOnly() {
        // The candidate-probe + shell-fallback branch is POSIX; Windows has its own PATH search.
        assumeFalse(System.getProperty("os.name").orEmpty().lowercase().contains("windows"))
    }

    @Test
    fun `an explicit configured path is returned unchanged`() {
        assertEquals(
            "/opt/custom/tool",
            CliBinaryResolver.resolve(fake, "/opt/custom/tool", shellResolver = { null }),
        )
    }

    @Test
    fun `falls back to the bare binary name when nothing resolves`() {
        assertEquals(fake, CliBinaryResolver.resolve(fake, fake, shellResolver = { null }))
    }

    @Test
    fun `uses the shell resolver when the well-known dirs miss`() {
        assertEquals(
            "/from/shell/bin/$fake",
            CliBinaryResolver.resolve(fake, fake, shellResolver = { "/from/shell/bin/$it" }),
        )
    }

    @Test
    fun `the shell resolver is queried with the requested binary name`() {
        var asked: String? = null
        CliBinaryResolver.resolve(fake, fake, shellResolver = { asked = it; null })
        assertEquals(fake, asked)
    }

    @Test
    fun `a configured value equal to the binary name is ignored (probes instead)`() {
        // configured == binary, so it is not treated as an explicit path; with no shell hit it falls
        // through to the bare name rather than returning early with a real path.
        assertEquals(fake, CliBinaryResolver.resolve(fake, fake, shellResolver = { null }))
    }
}
