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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AtomicFilesTest {

    private lateinit var dir: Path

    @Before fun setUp() { dir = Files.createTempDirectory("clawdea-atomic-") }
    @After fun tearDown() { dir.toFile().deleteRecursively() }

    @Test
    fun `writes content and overwrites an existing file`() {
        val target = dir.resolve("out.txt")
        AtomicFiles.writeAtomically(target, "first")
        assertEquals("first", Files.readString(target))
        AtomicFiles.writeAtomically(target, "second")
        assertEquals("second", Files.readString(target))
    }

    @Test
    fun `creates missing parent directories`() {
        val target = dir.resolve("nested/deeper/out.txt")
        AtomicFiles.writeAtomically(target, "hi")
        assertEquals("hi", Files.readString(target))
    }

    @Test
    fun `leaves no temp file behind`() {
        val target = dir.resolve("out.txt")
        AtomicFiles.writeAtomically(target, "data")
        val leftovers = Files.list(dir).use { s -> s.filter { it.fileName.toString().contains(".tmp") }.count() }
        assertTrue("no .tmp leftovers", leftovers == 0L)
    }
}
