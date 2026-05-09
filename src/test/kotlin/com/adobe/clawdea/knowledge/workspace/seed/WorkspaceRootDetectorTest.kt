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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceRootDetectorTest {
    private fun mkGitRepo(parent: Path, name: String): Path {
        val d = Files.createDirectory(parent.resolve(name))
        Files.createDirectory(d.resolve(".git"))
        return d
    }
    /**
     * Returns a blocklist that contains every ancestor of [tmp] up to the filesystem root.
     * Required because the system temp directory may itself contain stray `.git` subdirs
     * (e.g. from previous test runs or other tools) which would otherwise make the temp
     * directory's parent qualify as a workspace root and leak into assertions.
     * Both the absolute and realpath forms are added to defeat /var → /private/var symlinking on macOS.
     */
    private fun ancestorBlocklist(tmp: Path): Set<String> {
        val out = mutableSetOf<String>()
        var p: Path? = tmp.parent
        while (p != null) {
            out.add(p.toString())
            out.add(p.toRealPath().toString())
            p = p.parent
        }
        return out
    }

    @Test fun `parent with two git-repo siblings qualifies`() {
        val tmp = Files.createTempDirectory("root-detect")
        mkGitRepo(tmp, "a"); val b = mkGitRepo(tmp, "b")
        assertEquals(listOf(tmp.toRealPath()),
            WorkspaceRootDetector.detect(b, blocklist = ancestorBlocklist(tmp)).map { it.toRealPath() })
    }
    @Test fun `parent with a single git-repo does not qualify`() {
        val tmp = Files.createTempDirectory("root-detect")
        val a = mkGitRepo(tmp, "only")
        assertEquals(emptyList<Path>(), WorkspaceRootDetector.detect(a, blocklist = ancestorBlocklist(tmp) + tmp.toString()))
    }
    @Test fun `nested workspaces both qualify, deepest first`() {
        val tmp = Files.createTempDirectory("root-detect")
        val outer = Files.createDirectory(tmp.resolve("outer"))
        Files.createDirectory(outer.resolve(".git"))
        mkGitRepo(outer, "outer-b")
        val innerA = mkGitRepo(outer, "inner-a")
        val proj = mkGitRepo(innerA, "proj")
        mkGitRepo(innerA, "sib")
        assertEquals(
            listOf(innerA.toRealPath(), outer.toRealPath()),
            WorkspaceRootDetector.detect(proj, blocklist = ancestorBlocklist(tmp) + tmp.toString()).map { it.toRealPath() }
        )
    }
    @Test fun `blocklist filters out matching ancestors`() {
        val tmp = Files.createTempDirectory("root-detect")
        mkGitRepo(tmp, "a"); val b = mkGitRepo(tmp, "b")
        // Blocklist tmp (the only ancestor that would qualify), plus everything above it
        // (the system temp dir may contain unrelated `.git` siblings from other tools).
        assertEquals(emptyList<Path>(),
            WorkspaceRootDetector.detect(b, blocklist = ancestorBlocklist(tmp) + tmp.toString() + tmp.toRealPath().toString()))
    }
    @Test fun `default blocklist contains HOME`() {
        assertTrue(WorkspaceRootDetector.DEFAULT_BLOCKLIST.contains(System.getProperty("user.home")))
    }
}
