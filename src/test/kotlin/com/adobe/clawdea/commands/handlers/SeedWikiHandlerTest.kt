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

class SeedWikiHandlerTest {

    @Test
    fun `parsePlacement maps labels case-insensitively`() {
        assertEquals(SeedWikiHandler.Placement.LOCAL_ONLY, SeedWikiHandler.parsePlacement("local only"))
        assertEquals(SeedWikiHandler.Placement.LOCAL_ONLY, SeedWikiHandler.parsePlacement("Local Only"))
        assertEquals(SeedWikiHandler.Placement.SHAREABLE, SeedWikiHandler.parsePlacement("shareable via git"))
        assertEquals(SeedWikiHandler.Placement.SHAREABLE, SeedWikiHandler.parsePlacement("SHAREABLE VIA GIT"))
    }

    @Test
    fun `parsePlacement returns null for unknown labels and null input`() {
        assertNull(SeedWikiHandler.parsePlacement(null))
        assertNull(SeedWikiHandler.parsePlacement(""))
        assertNull(SeedWikiHandler.parsePlacement("global"))
        assertNull(SeedWikiHandler.parsePlacement("nothing"))
    }

    @Test
    fun `buildQuestion offers exactly two placement options labeled local-only and shareable`() {
        val q = SeedWikiHandler.buildQuestion()
        val first = q.questions.single()
        assertEquals(false, first.multiSelect)
        assertEquals(
            listOf(SeedWikiHandler.LOCAL_ONLY_LABEL, SeedWikiHandler.SHAREABLE_LABEL),
            first.options.map { it.label },
        )
        assertTrue("each option needs a description", first.options.all { it.description.isNotBlank() })
    }

    @Test
    fun `buildQuestion includes a freeform path field with the default shareable prefill`() {
        val q = SeedWikiHandler.buildQuestion()
        val ff = q.questions.single().freeformInput
        assertNotNull("freeformInput must be present so the user can edit the shareable path", ff)
        assertEquals(SeedWikiHandler.DEFAULT_SHAREABLE_WIKI_PATH, ff?.prefill)
        assertEquals(SeedWikiHandler.DEFAULT_SHAREABLE_WIKI_PATH, ff?.placeholder)
        assertNotNull(ff?.label)
        assertTrue(
            "label must clarify the field is only used for the shareable path, got: ${ff?.label}",
            ff?.label?.contains(SeedWikiHandler.SHAREABLE_LABEL) == true,
        )
    }

    @Test
    fun `buildQuestion preserves an explicit shareable prefill verbatim`() {
        val q = SeedWikiHandler.buildQuestion(shareablePathPrefill = "my/custom/wiki")
        assertEquals("my/custom/wiki", q.questions.single().freeformInput?.prefill)
    }

    @Test
    fun `execute without basePath emits a no-base-path error and never asks the question`() {
        val handler = SeedWikiHandler(stubProject(basePath = null)) { error("expansion must not run") }
        val htmlBuf = StringBuilder()
        var asked = false
        var dispatched: String? = null
        handler.execute("", CommandContext(
            appendHtml = { htmlBuf.append(it) },
            showNotification = {},
            askQuestion = { _, _ -> asked = true },
            dispatchToBridge = { dispatched = it },
        ))
        assertTrue(htmlBuf.toString().contains("no project base path"))
        assertFalse("must not prompt when there's no project base", asked)
        assertNull(dispatched)
    }

    @Test
    fun `execute headless (no askQuestion) falls back to the default-local expansion via dispatch`() {
        val tmp = Files.createTempDirectory("seed-wiki-headless").toAbsolutePath()
        try {
            val seenPath = mutableListOf<String>()
            val handler = SeedWikiHandler(stubProject(basePath = tmp.toString())) { wikiPathRel ->
                seenPath += wikiPathRel
                "EXPANDED($wikiPathRel)"
            }
            var dispatched: String? = null
            handler.execute("", CommandContext(
                appendHtml = {},
                showNotification = {},
                askQuestion = null,
                dispatchToBridge = { dispatched = it },
            ))
            assertEquals(listOf(SeedWikiHandler.DEFAULT_LOCAL_WIKI_PATH), seenPath)
            assertEquals("EXPANDED(${SeedWikiHandler.DEFAULT_LOCAL_WIKI_PATH})", dispatched)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `execute on cancel prints a cancellation message and never expands or dispatches`() {
        val tmp = Files.createTempDirectory("seed-wiki-cancel").toAbsolutePath()
        try {
            val handler = SeedWikiHandler(stubProject(basePath = tmp.toString())) { error("expansion must not run") }
            val htmlBuf = StringBuilder()
            var dispatched: String? = null
            handler.execute("", CommandContext(
                appendHtml = { htmlBuf.append(it) },
                showNotification = {},
                askQuestion = { _, onResolve -> onResolve(null) },
                dispatchToBridge = { dispatched = it },
            ))
            assertTrue("expected cancellation message, got: $htmlBuf", htmlBuf.toString().contains("cancelled"))
            assertNull(dispatched)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `execute on Submit with unknown placement label bails before any setup`() {
        val tmp = Files.createTempDirectory("seed-wiki-unknown").toAbsolutePath()
        try {
            val handler = SeedWikiHandler(stubProject(basePath = tmp.toString())) { error("expansion must not run") }
            val htmlBuf = StringBuilder()
            var dispatched: String? = null
            handler.execute("", CommandContext(
                appendHtml = { htmlBuf.append(it) },
                showNotification = {},
                askQuestion = { _, onResolve ->
                    onResolve(HandlerQuestionAnswers(answers = emptyMap(), freeforms = emptyMap()))
                },
                dispatchToBridge = { dispatched = it },
            ))
            assertTrue(
                "expected placement-required error, got: $htmlBuf",
                htmlBuf.toString().contains("'${SeedWikiHandler.LOCAL_ONLY_LABEL}'"),
            )
            assertNull(dispatched)
            assertFalse(Files.exists(tmp.resolve(".clawdea").resolve("config.json")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `execute on local-only Submit dispatches the default-local expansion and writes no config`() {
        val tmp = Files.createTempDirectory("seed-wiki-local").toAbsolutePath()
        try {
            val seenPath = mutableListOf<String>()
            val handler = SeedWikiHandler(stubProject(basePath = tmp.toString())) { wikiPathRel ->
                seenPath += wikiPathRel
                "PROMPT($wikiPathRel)"
            }
            var dispatched: String? = null
            handler.execute("", CommandContext(
                appendHtml = {},
                showNotification = {},
                askQuestion = { _, onResolve ->
                    onResolve(HandlerQuestionAnswers(
                        answers = mapOf("q" to SeedWikiHandler.LOCAL_ONLY_LABEL),
                        freeforms = mapOf("q" to "docs/llm-wiki" /* should be ignored */),
                    ))
                },
                dispatchToBridge = { dispatched = it },
            ))
            assertEquals(listOf(SeedWikiHandler.DEFAULT_LOCAL_WIKI_PATH), seenPath)
            assertEquals("PROMPT(${SeedWikiHandler.DEFAULT_LOCAL_WIKI_PATH})", dispatched)
            assertFalse("local-only must not write team config", Files.exists(tmp.resolve(".clawdea").resolve("config.json")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `execute on shareable Submit with bad path rejects without expansion or dispatch`() {
        val tmp = Files.createTempDirectory("seed-wiki-bad-path").toAbsolutePath()
        try {
            val handler = SeedWikiHandler(stubProject(basePath = tmp.toString())) { error("expansion must not run") }
            val htmlBuf = StringBuilder()
            var dispatched: String? = null
            handler.execute("", CommandContext(
                appendHtml = { htmlBuf.append(it) },
                showNotification = {},
                askQuestion = { _, onResolve ->
                    onResolve(HandlerQuestionAnswers(
                        answers = mapOf("q" to SeedWikiHandler.SHAREABLE_LABEL),
                        freeforms = mapOf("q" to "/absolute/path"),
                    ))
                },
                dispatchToBridge = { dispatched = it },
            ))
            assertTrue("expected validation error, got: $htmlBuf", htmlBuf.toString().contains("project-relative"))
            assertNull(dispatched)
            assertFalse(Files.exists(tmp.resolve(".clawdea").resolve("config.json")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `execute on shareable Submit (Application-less) writes config and dispatches the path-aware expansion`() {
        // No IntelliJ Application is registered in this unit test, so the
        // handler's `executeOnPooledThread` fallback runs the heavy block
        // synchronously. That makes `dispatch` observable inline.
        val tmp = Files.createTempDirectory("seed-wiki-shareable").toAbsolutePath()
        try {
            val seenPath = mutableListOf<String>()
            val handler = SeedWikiHandler(stubProject(basePath = tmp.toString())) { wikiPathRel ->
                seenPath += wikiPathRel
                "PROMPT($wikiPathRel)"
            }
            var dispatched: String? = null
            handler.execute("", CommandContext(
                appendHtml = {},
                showNotification = {},
                askQuestion = { _, onResolve ->
                    onResolve(HandlerQuestionAnswers(
                        answers = mapOf("q" to SeedWikiHandler.SHAREABLE_LABEL),
                        freeforms = mapOf("q" to "docs/llm-wiki"),
                    ))
                },
                dispatchToBridge = { dispatched = it },
            ))
            assertEquals(listOf("docs/llm-wiki"), seenPath)
            assertEquals("PROMPT(docs/llm-wiki)", dispatched)
            // Team-mode side effects:
            assertEquals(
                """{"wikiPath":"docs/llm-wiki"}""",
                Files.readString(tmp.resolve(".clawdea").resolve("config.json")),
            )
            assertTrue(
                Files.readString(tmp.resolve(".gitignore")).contains(".clawdea/wiki-state.local.json"),
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `execute with explicit args prefills the freeform field`() {
        val tmp = Files.createTempDirectory("seed-wiki-prefill").toAbsolutePath()
        try {
            val handler = SeedWikiHandler(stubProject(basePath = tmp.toString())) { _ -> "" }
            var captured: AskUserQuestionInput? = null
            handler.execute("custom/wiki", CommandContext(
                appendHtml = {},
                showNotification = {},
                askQuestion = { input, _ -> captured = input /* never resolved */ },
                dispatchToBridge = {},
            ))
            assertEquals("custom/wiki", captured?.questions?.single()?.freeformInput?.prefill)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * Stub [Project] for bail-path tests. Mirrors the pattern used in
     * `WikiRelocateHandlerTest`: only `getBasePath` and `getName` are wired;
     * service lookups return null, which the shareable-setup heavy block
     * handles via `?.onMassFileChange()` (no-op when the service is absent).
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
}
