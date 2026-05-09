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
package com.adobe.clawdea.chat.permission

import com.adobe.clawdea.chat.MessageRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserQuestionRendererTest {

    private val renderer = AskUserQuestionRenderer(MessageRenderer())

    private fun sample(multiSelect: Boolean = false) = AskUserQuestionInput(
        questions = listOf(
            AskUserQuestionInput.Question(
                question = "Which approach?",
                header = "Approach",
                multiSelect = multiSelect,
                options = listOf(
                    AskUserQuestionInput.Option("Option A", "First choice"),
                    AskUserQuestionInput.Option("Option B", "Second choice"),
                ),
            ),
        ),
    )

    @Test
    fun `renders submit and cancel buttons`() {
        val html = renderer.renderCard("perm-1", sample())
        assertTrue("submit button missing: $html", html.contains("data-action=\"question-submit\""))
        assertTrue("cancel button missing: $html", html.contains("data-action=\"question-cancel\""))
        assertTrue(html.contains("data-permission-id=\"perm-1\""))
    }

    @Test
    fun `single-select question uses radio inputs`() {
        val html = renderer.renderCard("perm-1", sample(multiSelect = false))
        assertTrue(html.contains("type=\"radio\""))
        assertFalse(html.contains("type=\"checkbox\""))
    }

    @Test
    fun `multi-select question uses checkbox inputs and a badge`() {
        val html = renderer.renderCard("perm-1", sample(multiSelect = true))
        assertTrue(html.contains("type=\"checkbox\""))
        assertFalse(html.contains("type=\"radio\""))
        assertTrue(html.contains("question-multi-badge"))
    }

    @Test
    fun `radios in the same question share a name attribute so picking one clears the others`() {
        val html = renderer.renderCard("perm-1", sample(multiSelect = false))
        val nameRegex = Regex("""name="(perm-perm-1-q\d+)"""")
        val names = nameRegex.findAll(html).map { it.groupValues[1] }.toSet()
        assertTrue("expected a single shared radio group name, got $names", names.size == 1)
    }

    @Test
    fun `each radio carries data-question and data-label so the bridge can collect answers`() {
        val html = renderer.renderCard("perm-1", sample())
        assertTrue(html.contains("data-question=\"Which approach?\""))
        assertTrue(html.contains("data-label=\"Option A\""))
        assertTrue(html.contains("data-label=\"Option B\""))
    }

    @Test
    fun `option labels and descriptions are HTML-escaped`() {
        val input = AskUserQuestionInput(
            questions = listOf(
                AskUserQuestionInput.Question(
                    question = "<script>",
                    header = "<x>",
                    multiSelect = false,
                    options = listOf(AskUserQuestionInput.Option("a&b", "<i>")),
                ),
            ),
        )
        val html = renderer.renderCard("perm-1", input)
        assertFalse("<script> must be escaped: $html", html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("a&amp;b"))
    }

    @Test
    fun `header chip is omitted when header is blank`() {
        val input = AskUserQuestionInput(
            questions = listOf(
                AskUserQuestionInput.Question(
                    question = "q",
                    header = "",
                    multiSelect = false,
                    options = listOf(AskUserQuestionInput.Option("a", "")),
                ),
            ),
        )
        val html = renderer.renderCard("perm-1", input)
        assertFalse(html.contains("question-chip"))
    }

    @Test
    fun `option description is omitted when description is blank`() {
        val input = AskUserQuestionInput(
            questions = listOf(
                AskUserQuestionInput.Question(
                    question = "q",
                    header = "h",
                    multiSelect = false,
                    options = listOf(AskUserQuestionInput.Option("a", "")),
                ),
            ),
        )
        val html = renderer.renderCard("perm-1", input)
        assertFalse(html.contains("question-option-desc"))
    }

    @Test
    fun `buildResolvedScript answered branch references answered status class`() {
        val js = renderer.buildResolvedScript(
            "perm-1",
            mapOf("Which approach?" to "Option A"),
            skipped = false,
        )
        assertTrue(js.contains("question-status-answered"))
        assertTrue(js.contains("Submitted"))
        assertTrue(js.contains("Which approach?"))
    }

    @Test
    fun `buildResolvedScript skipped branch references skipped status class`() {
        val js = renderer.buildResolvedScript("perm-1", emptyMap(), skipped = true)
        assertTrue(js.contains("question-status-skipped"))
        assertTrue(js.contains("Skipped"))
        assertFalse(js.contains("question-answers"))
    }
}
