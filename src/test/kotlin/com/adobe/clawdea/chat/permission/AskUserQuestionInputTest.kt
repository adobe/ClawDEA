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

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserQuestionInputTest {

    private val sampleJson = """
        {
          "questions": [
            {
              "question": "Which library should we use?",
              "header": "Library",
              "multiSelect": false,
              "options": [
                {"label": "Lib A", "description": "Fast and small"},
                {"label": "Lib B", "description": "Battle-tested"}
              ]
            },
            {
              "question": "Which features?",
              "header": "Features",
              "multiSelect": true,
              "options": [
                {"label": "Auth", "description": ""},
                {"label": "Logging", "description": "Structured logs"}
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parse extracts every question and option`() {
        val input = AskUserQuestionInput.parse(sampleJson)
        assertNotNull(input)
        assertEquals(2, input!!.questions.size)
        assertEquals("Which library should we use?", input.questions[0].question)
        assertEquals("Library", input.questions[0].header)
        assertFalse(input.questions[0].multiSelect)
        assertEquals(2, input.questions[0].options.size)
        assertEquals("Lib A", input.questions[0].options[0].label)
        assertEquals("Fast and small", input.questions[0].options[0].description)
        assertTrue(input.questions[1].multiSelect)
    }

    @Test
    fun `parse returns null for blank input`() {
        assertNull(AskUserQuestionInput.parse(""))
        assertNull(AskUserQuestionInput.parse("   "))
    }

    @Test
    fun `parse returns null for malformed JSON`() {
        assertNull(AskUserQuestionInput.parse("{not-valid}"))
    }

    @Test
    fun `parse returns null when questions key is missing`() {
        assertNull(AskUserQuestionInput.parse("""{"foo":"bar"}"""))
    }

    @Test
    fun `parse returns null when questions array is empty`() {
        assertNull(AskUserQuestionInput.parse("""{"questions":[]}"""))
    }

    @Test
    fun `parse skips malformed questions but keeps the rest`() {
        val json = """
            {"questions":[
              {"question":"OK","header":"H","multiSelect":false,"options":[{"label":"L","description":"D"}]},
              "not-an-object",
              {"header":"missing question text"}
            ]}
        """.trimIndent()
        val input = AskUserQuestionInput.parse(json)
        assertNotNull(input)
        assertEquals(1, input!!.questions.size)
        assertEquals("OK", input.questions[0].question)
    }

    @Test
    fun `parse defaults multiSelect to false when missing`() {
        val json = """
            {"questions":[{"question":"q","header":"h","options":[{"label":"a","description":""}]}]}
        """.trimIndent()
        val input = AskUserQuestionInput.parse(json)
        assertNotNull(input)
        assertFalse(input!!.questions[0].multiSelect)
    }

    @Test
    fun `buildUpdatedInput preserves original fields and injects answers`() {
        val updated = AskUserQuestionInput.buildUpdatedInput(
            sampleJson,
            mapOf(
                "Which library should we use?" to "Lib A",
                "Which features?" to "Auth, Logging",
            ),
        )
        val obj = JsonParser.parseString(updated).asJsonObject
        // Original questions array preserved
        assertTrue(obj.has("questions"))
        assertEquals(2, obj.getAsJsonArray("questions").size())
        // Answers folded in
        val answers = obj.getAsJsonObject("answers")
        assertEquals("Lib A", answers.get("Which library should we use?").asString)
        assertEquals("Auth, Logging", answers.get("Which features?").asString)
    }

    @Test
    fun `buildUpdatedInput handles missing original input`() {
        val updated = AskUserQuestionInput.buildUpdatedInput("", mapOf("q" to "a"))
        val obj = JsonParser.parseString(updated).asJsonObject
        assertEquals("a", obj.getAsJsonObject("answers").get("q").asString)
    }

    @Test
    fun `buildUpdatedInput skips blank keys and values`() {
        val updated = AskUserQuestionInput.buildUpdatedInput(
            """{"questions":[]}""",
            mapOf("" to "a", "q" to "", "real" to "yes"),
        )
        val obj = JsonParser.parseString(updated).asJsonObject
        val answers = obj.getAsJsonObject("answers")
        assertEquals(1, answers.size())
        assertEquals("yes", answers.get("real").asString)
    }

    @Test
    fun `buildUpdatedInput overwrites prior answers when present`() {
        val original = """{"questions":[],"answers":{"q":"old"}}"""
        val updated = AskUserQuestionInput.buildUpdatedInput(original, mapOf("q" to "new"))
        val obj = JsonParser.parseString(updated).asJsonObject
        assertEquals("new", obj.getAsJsonObject("answers").get("q").asString)
    }

    @Test
    fun `parse reads freeformInput when present`() {
        val json = """
            {"questions":[{
              "question":"Where to?",
              "header":"Path",
              "multiSelect":false,
              "options":[{"label":"Move","description":""}],
              "freeformInput":{"prefill":"docs/llm-wiki","label":"Path:","placeholder":"docs/llm-wiki"}
            }]}
        """.trimIndent()
        val input = AskUserQuestionInput.parse(json)
        assertNotNull(input)
        val ff = input!!.questions[0].freeformInput
        assertNotNull(ff)
        assertEquals("docs/llm-wiki", ff!!.prefill)
        assertEquals("Path:", ff.label)
        assertEquals("docs/llm-wiki", ff.placeholder)
    }

    @Test
    fun `parse leaves freeformInput null when absent`() {
        val input = AskUserQuestionInput.parse(sampleJson)
        assertNotNull(input)
        assertNull(input!!.questions[0].freeformInput)
        assertNull(input.questions[1].freeformInput)
    }

    @Test
    fun `parse tolerates malformed freeformInput object`() {
        // freeformInput is not an object (just a string) — should be ignored
        val notAnObject = """
            {"questions":[{
              "question":"q","header":"h","multiSelect":false,
              "options":[{"label":"a","description":""}],
              "freeformInput":"oops"
            }]}
        """.trimIndent()
        val input1 = AskUserQuestionInput.parse(notAnObject)
        assertNotNull(input1)
        assertNull(input1!!.questions[0].freeformInput)

        // freeformInput is an object but missing the required "prefill"
        val missingPrefill = """
            {"questions":[{
              "question":"q","header":"h","multiSelect":false,
              "options":[{"label":"a","description":""}],
              "freeformInput":{"label":"only label"}
            }]}
        """.trimIndent()
        val input2 = AskUserQuestionInput.parse(missingPrefill)
        assertNotNull(input2)
        assertNull(input2!!.questions[0].freeformInput)
    }

    @Test
    fun `parse defaults freeformInput label and placeholder to null when omitted`() {
        val json = """
            {"questions":[{
              "question":"q","header":"h","multiSelect":false,
              "options":[{"label":"a","description":""}],
              "freeformInput":{"prefill":"value-only"}
            }]}
        """.trimIndent()
        val input = AskUserQuestionInput.parse(json)
        assertNotNull(input)
        val ff = input!!.questions[0].freeformInput
        assertNotNull(ff)
        assertEquals("value-only", ff!!.prefill)
        assertNull(ff.label)
        assertNull(ff.placeholder)
    }
}
