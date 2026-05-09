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
package com.adobe.clawdea.knowledge.workspace.seed

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CandidateScannerTest {
    private fun mkGitRepo(parent: Path, name: String): Path {
        val d = Files.createDirectory(parent.resolve(name))
        Files.createDirectory(d.resolve(".git"))
        return d
    }

    @Test fun `filesystem siblings are picked up`() {
        val ws = Files.createTempDirectory("scanner")
        val a = mkGitRepo(ws, "a"); val b = mkGitRepo(ws, "b")
        val out = CandidateScanner.scan(ws, openProjects = emptyList(), recentProjects = emptyList())
        assertEquals(setOf(a.toRealPath(), b.toRealPath()), out.map { it.toRealPath() }.toSet())
    }

    @Test fun `IntelliJ open project under the root is included, outside is filtered`() {
        val ws = Files.createTempDirectory("scanner"); val a = mkGitRepo(ws, "a")
        val outside = Files.createTempDirectory("outside"); Files.createDirectory(outside.resolve(".git"))
        val out = CandidateScanner.scan(ws, openProjects = listOf(a, outside), recentProjects = emptyList())
        assertEquals(setOf(a.toRealPath()), out.map { it.toRealPath() }.toSet())
    }

    @Test fun `recent projects deduplicate`() {
        val ws = Files.createTempDirectory("scanner"); val a = mkGitRepo(ws, "a")
        val out = CandidateScanner.scan(ws, openProjects = listOf(a), recentProjects = listOf(a))
        assertEquals(listOf(a.toRealPath()), out.map { it.toRealPath() })
    }

    @Test fun `non-git-repo siblings are skipped`() {
        val ws = Files.createTempDirectory("scanner"); val a = mkGitRepo(ws, "a")
        Files.createDirectory(ws.resolve("not-a-repo"))
        val out = CandidateScanner.scan(ws, openProjects = emptyList(), recentProjects = emptyList())
        assertEquals(setOf(a.toRealPath()), out.map { it.toRealPath() }.toSet())
    }
}
