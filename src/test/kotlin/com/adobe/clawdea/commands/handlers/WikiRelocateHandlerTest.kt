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
package com.adobe.clawdea.commands.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class WikiRelocateHandlerTest {

    @Test fun `validatePath rejects absolute paths`() {
        val err = WikiRelocateHandler.validatePath("/abs/path")
        assertNotNull(err)
    }

    @Test fun `validatePath rejects parent traversal`() {
        val err = WikiRelocateHandler.validatePath("../escape")
        assertNotNull(err)
    }

    @Test fun `validatePath rejects empty input`() {
        val err = WikiRelocateHandler.validatePath("")
        assertNotNull(err)
    }

    @Test fun `validatePath accepts a relative subpath`() {
        assertNull(WikiRelocateHandler.validatePath("docs/llm-wiki"))
    }

    @Test fun `writeConfig creates clawdea config json with wikiPath`() {
        val tmp = Files.createTempDirectory("relocate-cfg")
        try {
            WikiRelocateHandler.writeConfig(projectBase = tmp, wikiPath = "docs/llm-wiki")
            val content = Files.readString(tmp.resolve(".clawdea").resolve("config.json"))
            assertEquals("""{"wikiPath":"docs/llm-wiki"}""", content)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `appendGitignore creates file with entry when missing`() {
        val tmp = Files.createTempDirectory("relocate-gi-new")
        try {
            WikiRelocateHandler.appendGitignore(tmp, ".clawdea/wiki-state.local.json")
            val content = Files.readString(tmp.resolve(".gitignore"))
            assertEquals(".clawdea/wiki-state.local.json\n", content)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `appendGitignore appends entry to existing file`() {
        val tmp = Files.createTempDirectory("relocate-gi-add")
        try {
            Files.writeString(tmp.resolve(".gitignore"), "build/\n")
            WikiRelocateHandler.appendGitignore(tmp, ".clawdea/wiki-state.local.json")
            val content = Files.readString(tmp.resolve(".gitignore"))
            assertEquals("build/\n.clawdea/wiki-state.local.json\n", content)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `appendGitignore is idempotent (no duplicate entry)`() {
        val tmp = Files.createTempDirectory("relocate-gi-dup")
        try {
            Files.writeString(tmp.resolve(".gitignore"), "build/\n.clawdea/wiki-state.local.json\n")
            WikiRelocateHandler.appendGitignore(tmp, ".clawdea/wiki-state.local.json")
            val content = Files.readString(tmp.resolve(".gitignore"))
            assertEquals("build/\n.clawdea/wiki-state.local.json\n", content)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `buildQuestion produces three options labeled Move Copy Nothing`() {
        val q = WikiRelocateHandler.buildQuestion(newPath = "docs/llm-wiki")
        val first = q.questions.single()
        assertEquals(false, first.multiSelect)
        assertEquals(listOf("Move", "Copy", "Nothing"), first.options.map { it.label })
        // Each option carries a description.
        assert(first.options.all { it.description.isNotBlank() })
        assert(first.question.contains("docs/llm-wiki"))
    }

    @Test fun `applyAction NOTHING leaves files where they are`() {
        val tmp = Files.createTempDirectory("relocate-nothing")
        try {
            val oldDir = Files.createDirectories(tmp.resolve("old"))
            val newDir = tmp.resolve("new")
            Files.writeString(oldDir.resolve("foo.md"), "x")
            WikiRelocateHandler.applyAction(
                oldDir = oldDir,
                newDir = newDir,
                action = WikiRelocateHandler.Action.NOTHING,
                gitMove = { _, _ -> true },
            )
            assert(Files.exists(oldDir.resolve("foo.md")))
            assert(!Files.exists(newDir.resolve("foo.md")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `applyAction COPY duplicates the tree without removing originals`() {
        val tmp = Files.createTempDirectory("relocate-copy")
        try {
            val oldDir = Files.createDirectories(tmp.resolve("old"))
            val newDir = tmp.resolve("new")
            Files.writeString(oldDir.resolve("foo.md"), "x")
            Files.createDirectories(oldDir.resolve("concepts"))
            Files.writeString(oldDir.resolve("concepts").resolve("bar.md"), "y")
            WikiRelocateHandler.applyAction(
                oldDir = oldDir,
                newDir = newDir,
                action = WikiRelocateHandler.Action.COPY,
                gitMove = { _, _ -> true },
            )
            assertEquals("x", Files.readString(oldDir.resolve("foo.md")))
            assertEquals("x", Files.readString(newDir.resolve("foo.md")))
            assertEquals("y", Files.readString(newDir.resolve("concepts").resolve("bar.md")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `applyAction MOVE delegates each file to gitMove and falls back to filesystem move`() {
        val tmp = Files.createTempDirectory("relocate-move")
        try {
            val oldDir = Files.createDirectories(tmp.resolve("old"))
            val newDir = tmp.resolve("new")
            Files.writeString(oldDir.resolve("tracked.md"), "t")
            Files.writeString(oldDir.resolve("untracked.md"), "u")
            val gitMoveCalls = mutableListOf<Pair<java.nio.file.Path, java.nio.file.Path>>()
            WikiRelocateHandler.applyAction(
                oldDir = oldDir,
                newDir = newDir,
                action = WikiRelocateHandler.Action.MOVE,
                gitMove = { src, dst ->
                    gitMoveCalls += src to dst
                    // Simulate: tracked.md is tracked (git mv succeeds), untracked.md isn't.
                    if (src.fileName.toString() == "tracked.md") {
                        java.nio.file.Files.createDirectories(dst.parent)
                        java.nio.file.Files.move(src, dst)
                        true
                    } else false
                },
            )
            assertEquals("t", Files.readString(newDir.resolve("tracked.md")))
            assertEquals("u", Files.readString(newDir.resolve("untracked.md")))
            assert(!Files.exists(oldDir.resolve("tracked.md")))
            assert(!Files.exists(oldDir.resolve("untracked.md")))
            assertEquals(2, gitMoveCalls.size)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
