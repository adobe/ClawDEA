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

import com.adobe.clawdea.chat.permission.AskUserQuestionInput
import com.adobe.clawdea.chat.permission.HandlerQuestionAnswers
import com.adobe.clawdea.commands.CommandContext
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val q = WikiRelocateHandler.buildQuestion(prefillPath = "docs/llm-wiki")
        val first = q.questions.single()
        assertEquals(false, first.multiSelect)
        assertEquals(listOf("Move", "Copy", "Nothing"), first.options.map { it.label })
        // Each option carries a description.
        assert(first.options.all { it.description.isNotBlank() })
    }

    @Test fun `buildQuestion includes a freeform input prefilled with the provided path`() {
        val q = WikiRelocateHandler.buildQuestion(prefillPath = "docs/llm-wiki")
        val freeform = q.questions.single().freeformInput
        assertNotNull("freeformInput must be present", freeform)
        assertEquals("docs/llm-wiki", freeform?.prefill)
        // Label/placeholder are present so the field renders with helpful chrome.
        assertNotNull(freeform?.label)
        assertNotNull(freeform?.placeholder)
    }

    @Test fun `buildQuestion preserves an arbitrary prefill path verbatim`() {
        val q = WikiRelocateHandler.buildQuestion(prefillPath = "my/custom/wiki")
        assertEquals("my/custom/wiki", q.questions.single().freeformInput?.prefill)
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

    // --- execute() path: tests that don't reach WikiLocator.getInstance ---
    //
    // The Submit happy path (action+path valid) needs `WikiLocator.getInstance(project)`
    // which requires a registered IntelliJ application; that's covered by the integration
    // tests run from the IDE runner. The bail-out paths below all exit before that line.

    @Test fun `execute without basePath emits a project-base-path error`() {
        val handler = WikiRelocateHandler(stubProject(basePath = null))
        val htmlBuf = StringBuilder()
        var asked = false
        handler.execute("docs/llm-wiki", CommandContext(
            appendHtml = { htmlBuf.append(it) },
            showNotification = {},
            askQuestion = { _, _ -> asked = true },
        ))
        assertTrue(htmlBuf.toString().contains("no project base path"))
        assertFalse("must not prompt when there's no project base", asked)
    }

    @Test fun `execute without askQuestion in context emits a no-interactive-UI error`() {
        val tmp = Files.createTempDirectory("wiki-no-ui").toAbsolutePath()
        try {
            val handler = WikiRelocateHandler(stubProject(basePath = tmp.toString()))
            val htmlBuf = StringBuilder()
            handler.execute("docs/llm-wiki", CommandContext(
                appendHtml = { htmlBuf.append(it) },
                showNotification = {},
                askQuestion = null,
            ))
            assertTrue(htmlBuf.toString().contains("no interactive UI"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `execute with empty args prompts with the default wiki-path prefill`() {
        val tmp = Files.createTempDirectory("wiki-prefill-default").toAbsolutePath()
        try {
            val handler = WikiRelocateHandler(stubProject(basePath = tmp.toString()))
            var captured: AskUserQuestionInput? = null
            handler.execute("", CommandContext(
                appendHtml = {},
                showNotification = {},
                askQuestion = { input, _ -> captured = input /* resolver never invoked */ },
            ))
            val ff = captured?.questions?.single()?.freeformInput
            assertNotNull("askQuestion must have been invoked with an input", ff)
            assertEquals(WikiRelocateHandler.DEFAULT_WIKI_PATH, ff?.prefill)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `execute with explicit args prefills the card with the given path`() {
        val tmp = Files.createTempDirectory("wiki-prefill-arg").toAbsolutePath()
        try {
            val handler = WikiRelocateHandler(stubProject(basePath = tmp.toString()))
            var captured: AskUserQuestionInput? = null
            handler.execute("custom/wiki", CommandContext(
                appendHtml = {},
                showNotification = {},
                askQuestion = { input, _ -> captured = input },
            ))
            assertEquals("custom/wiki", captured?.questions?.single()?.freeformInput?.prefill)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `execute on Skip writes no clawdea config and surfaces a cancelled message`() {
        val tmp = Files.createTempDirectory("wiki-skip").toAbsolutePath()
        try {
            val handler = WikiRelocateHandler(stubProject(basePath = tmp.toString()))
            val htmlBuf = StringBuilder()
            handler.execute("docs/llm-wiki", CommandContext(
                appendHtml = { htmlBuf.append(it) },
                showNotification = {},
                askQuestion = { _, onResolve -> onResolve(null) },
            ))
            assertTrue("expected cancellation message, got: $htmlBuf", htmlBuf.toString().contains("cancelled"))
            assertFalse(
                "Skip must not write .clawdea/config.json",
                Files.exists(tmp.resolve(".clawdea").resolve("config.json")),
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `execute on Submit with unknown action label bails before touching WikiLocator`() {
        val tmp = Files.createTempDirectory("wiki-bad-action").toAbsolutePath()
        try {
            val handler = WikiRelocateHandler(stubProject(basePath = tmp.toString()))
            val htmlBuf = StringBuilder()
            handler.execute("docs/llm-wiki", CommandContext(
                appendHtml = { htmlBuf.append(it) },
                showNotification = {},
                askQuestion = { _, onResolve ->
                    // The renderer should have inserted "Move"/"Copy"/"Nothing" but
                    // a corrupted submit (e.g. nothing selected) yields a missing or
                    // unknown label. The handler must refuse without writing config.
                    onResolve(HandlerQuestionAnswers(
                        answers = emptyMap(),
                        freeforms = mapOf("q" to "docs/llm-wiki"),
                    ))
                },
            ))
            assertTrue(
                "expected choose-action error, got: $htmlBuf",
                htmlBuf.toString().contains("Move, Copy, or Nothing"),
            )
            assertFalse(Files.exists(tmp.resolve(".clawdea").resolve("config.json")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `execute on Submit with invalid freeform path bails before touching WikiLocator`() {
        val tmp = Files.createTempDirectory("wiki-bad-path").toAbsolutePath()
        try {
            val handler = WikiRelocateHandler(stubProject(basePath = tmp.toString()))
            val htmlBuf = StringBuilder()
            handler.execute("docs/llm-wiki", CommandContext(
                appendHtml = { htmlBuf.append(it) },
                showNotification = {},
                askQuestion = { _, onResolve ->
                    onResolve(HandlerQuestionAnswers(
                        answers = mapOf("q" to "Nothing"),
                        freeforms = mapOf("q" to "/absolute/path"),
                    ))
                },
            ))
            assertTrue(
                "expected validation error, got: $htmlBuf",
                htmlBuf.toString().contains("project-relative"),
            )
            assertFalse(Files.exists(tmp.resolve(".clawdea").resolve("config.json")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * Stub [Project] for the execute() bail-out tests. The handler reads
     * `project.basePath` once at the top of `execute`; all other Project
     * methods are unused in the bail paths covered here. The full submit
     * path needs `WikiLocator.getInstance(project)`, which goes through the
     * IntelliJ application service registry and is exercised in the IDE
     * integration tests rather than this headless suite.
     */
    private fun stubProject(basePath: String?): Project {
        return java.lang.reflect.Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "getName" -> "stub-project"
                else -> null
            }
        } as Project
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

    @Test fun `applyAction MOVE removes the now-empty old wiki directory`() {
        val tmp = Files.createTempDirectory("relocate-move-cleanup")
        try {
            val oldDir = Files.createDirectories(tmp.resolve(".claude").resolve("wiki"))
            val newDir = tmp.resolve("docs").resolve("llm-wiki")
            Files.writeString(oldDir.resolve("index.md"), "x")
            Files.createDirectories(oldDir.resolve("concepts"))
            Files.writeString(oldDir.resolve("concepts").resolve("a.md"), "a")
            WikiRelocateHandler.applyAction(
                oldDir = oldDir,
                newDir = newDir,
                action = WikiRelocateHandler.Action.MOVE,
                gitMove = { _, _ -> false },
            )
            assertFalse("oldDir must be removed when empty after Move", Files.exists(oldDir))
            // The parent (.claude/) is left alone — only the wiki subtree is the
            // handler's responsibility.
            assertTrue(Files.exists(tmp.resolve(".claude")))
            assertEquals("x", Files.readString(newDir.resolve("index.md")))
            assertEquals("a", Files.readString(newDir.resolve("concepts").resolve("a.md")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `applyAction MOVE leaves oldDir alone when foreign files remain`() {
        // Defensive: if something we didn't move ends up in oldDir (e.g. a
        // sibling tool drops a file mid-relocate), the handler must not nuke
        // the directory. The cleanup walk keys on emptiness, so the presence
        // of any leftover file blocks deletion.
        val tmp = Files.createTempDirectory("relocate-move-foreign")
        try {
            val oldDir = Files.createDirectories(tmp.resolve("old"))
            val newDir = tmp.resolve("new")
            Files.writeString(oldDir.resolve("foo.md"), "x")
            // Simulate a "foreign" file that isn't a regular wiki page — applyAction
            // currently moves every regular file under oldDir, so to exercise the
            // "oldDir non-empty" branch we use a gitMove callback that drops a
            // marker file partway through the move.
            val marker = oldDir.resolve(".keep")
            WikiRelocateHandler.applyAction(
                oldDir = oldDir,
                newDir = newDir,
                action = WikiRelocateHandler.Action.MOVE,
                gitMove = { _, _ ->
                    Files.writeString(marker, "leftover")
                    false
                },
            )
            assertTrue("foreign file blocks oldDir cleanup", Files.exists(marker))
            assertTrue("oldDir must remain when not empty", Files.exists(oldDir))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // --- removeEmptyDefaultWikiTrees ---

    @Test fun `removeEmptyDefaultWikiTrees deletes an empty leftover wiki dir`() {
        val tmp = Files.createTempDirectory("relocate-empty-wiki")
        try {
            Files.createDirectories(tmp.resolve(".clawdea").resolve("wiki").resolve("concepts"))
            val newWiki = tmp.resolve("docs").resolve("llm-wiki")
            WikiRelocateHandler.removeEmptyDefaultWikiTrees(tmp, "wiki", newWiki)
            assertFalse("empty .clawdea/wiki must be removed", Files.exists(tmp.resolve(".clawdea").resolve("wiki")))
            // .clawdea itself is kept (holds config.json etc.).
            assertTrue(Files.exists(tmp.resolve(".clawdea")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `removeEmptyDefaultWikiTrees preserves a populated wiki dir`() {
        val tmp = Files.createTempDirectory("relocate-populated-wiki")
        try {
            Files.createDirectories(tmp.resolve(".clawdea").resolve("wiki"))
            Files.writeString(tmp.resolve(".clawdea").resolve("wiki").resolve("index.md"), "x")
            WikiRelocateHandler.removeEmptyDefaultWikiTrees(tmp, "wiki", tmp.resolve("docs/llm-wiki"))
            assertTrue("populated wiki must be preserved", Files.exists(tmp.resolve(".clawdea").resolve("wiki").resolve("index.md")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `removeEmptyDefaultWikiTrees never deletes the new team path`() {
        val tmp = Files.createTempDirectory("relocate-keep-team")
        try {
            // Pathological: team path is literally .clawdea/wiki and it's empty.
            val teamWiki = Files.createDirectories(tmp.resolve(".clawdea").resolve("wiki"))
            WikiRelocateHandler.removeEmptyDefaultWikiTrees(tmp, "wiki", teamWiki)
            assertTrue("the new team path must never be deleted", Files.exists(teamWiki))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // --- updateClaudeMdWikiLink ---

    @Test fun `updateClaudeMdWikiLink rewrites both the link text and the URL`() {
        val tmp = Files.createTempDirectory("claude-link-rewrite")
        try {
            val claudeMd = tmp.resolve("CLAUDE.md")
            Files.writeString(
                claudeMd,
                """
                |# CLAUDE.md
                |
                |## Wiki
                |
                |Detailed knowledge about individual subsystems lives in [`.claude/wiki/index.md`](.claude/wiki/index.md).
                |Concept pages cover entry points.
                |""".trimMargin(),
            )
            val updated = WikiRelocateHandler.updateClaudeMdWikiLink(
                projectBase = tmp,
                oldWikiRel = ".claude/wiki",
                newWikiRel = "docs/llm-wiki",
            )
            assertTrue("rewrite must report a change", updated)
            val content = Files.readString(claudeMd)
            assertTrue(
                "expected the link text to be rewritten, got: $content",
                content.contains("[`docs/llm-wiki/index.md`]"),
            )
            assertTrue(
                "expected the URL to be rewritten, got: $content",
                content.contains("](docs/llm-wiki/index.md)"),
            )
            assertFalse("old path must be gone, got: $content", content.contains(".claude/wiki/"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `updateClaudeMdWikiLink is a no-op when CLAUDE_md is missing`() {
        val tmp = Files.createTempDirectory("claude-link-missing")
        try {
            val updated = WikiRelocateHandler.updateClaudeMdWikiLink(
                projectBase = tmp,
                oldWikiRel = ".claude/wiki",
                newWikiRel = "docs/llm-wiki",
            )
            assertFalse(updated)
            assertFalse(Files.exists(tmp.resolve("CLAUDE.md")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `updateClaudeMdWikiLink is a no-op when CLAUDE_md does not mention the old path`() {
        val tmp = Files.createTempDirectory("claude-link-absent")
        try {
            val original = "# CLAUDE.md\n\n(no wiki link here yet)\n"
            Files.writeString(tmp.resolve("CLAUDE.md"), original)
            val updated = WikiRelocateHandler.updateClaudeMdWikiLink(
                projectBase = tmp,
                oldWikiRel = ".claude/wiki",
                newWikiRel = "docs/llm-wiki",
            )
            assertFalse(updated)
            assertEquals("file must be byte-identical", original, Files.readString(tmp.resolve("CLAUDE.md")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `updateClaudeMdWikiLink does not touch sibling paths sharing a prefix`() {
        // Regression guard for naive replace: ".claude/wiki" without the trailing
        // slash boundary would also match ".claude/wiki-archive/...".
        val tmp = Files.createTempDirectory("claude-link-prefix")
        try {
            val claudeMd = tmp.resolve("CLAUDE.md")
            Files.writeString(
                claudeMd,
                """
                |# CLAUDE.md
                |
                |Active wiki: [`.claude/wiki/index.md`](.claude/wiki/index.md)
                |Old archive (do not touch): [`.claude/wiki-archive/index.md`](.claude/wiki-archive/index.md)
                |""".trimMargin(),
            )
            WikiRelocateHandler.updateClaudeMdWikiLink(
                projectBase = tmp,
                oldWikiRel = ".claude/wiki",
                newWikiRel = "docs/llm-wiki",
            )
            val content = Files.readString(claudeMd)
            assertTrue(content.contains(".claude/wiki-archive/"))
            assertTrue(content.contains("docs/llm-wiki/index.md"))
            assertFalse("active wiki link's old path must be gone", content.contains(".claude/wiki/index.md"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `updateClaudeMdWikiLink is idempotent when paths are equal`() {
        val tmp = Files.createTempDirectory("claude-link-equal")
        try {
            val original = "[`.claude/wiki/index.md`](.claude/wiki/index.md)\n"
            Files.writeString(tmp.resolve("CLAUDE.md"), original)
            val updated = WikiRelocateHandler.updateClaudeMdWikiLink(
                projectBase = tmp,
                oldWikiRel = ".claude/wiki",
                newWikiRel = ".claude/wiki",
            )
            assertFalse("equal paths must not report a change", updated)
            assertEquals(original, Files.readString(tmp.resolve("CLAUDE.md")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `updateClaudeMdWikiLink rejects blank inputs`() {
        val tmp = Files.createTempDirectory("claude-link-blank")
        try {
            Files.writeString(tmp.resolve("CLAUDE.md"), "[`.claude/wiki/index.md`](.claude/wiki/index.md)\n")
            assertFalse(WikiRelocateHandler.updateClaudeMdWikiLink(tmp, "", "docs/llm-wiki"))
            assertFalse(WikiRelocateHandler.updateClaudeMdWikiLink(tmp, ".claude/wiki", ""))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
