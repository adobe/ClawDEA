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

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Typed view of the `AskUserQuestion` tool input the Claude CLI hands to our
 * `--permission-prompt-tool`. The CLI's checkPermissions returns
 * `{behavior: "ask", updatedInput: H}` which means we must echo the same shape
 * back with an `answers` field populated by the user's choices — otherwise the
 * tool runs with no answers and Claude sees an empty response.
 *
 * Schema (extracted from the bundled CLI binary):
 * ```
 * {
 *   "questions": [
 *     {
 *       "question": "...",
 *       "header": "...",
 *       "options": [{"label": "...", "description": "...", "preview"?: "..."}],
 *       "multiSelect": false
 *     }
 *   ],
 *   "answers"?: {"<question text>": "<label>"},
 *   "annotations"?: {...},
 *   "metadata"?: {...}
 * }
 * ```
 */
data class AskUserQuestionInput(
    val questions: List<Question>,
) {
    data class Question(
        val question: String,
        val header: String,
        val options: List<Option>,
        val multiSelect: Boolean,
        /**
         * Optional editable text field rendered alongside the radio/checkbox
         * options. The CLI never sends this — it exists for in-plugin question
         * cards that need both a chosen action AND a freeform value (e.g.
         * `/wiki-relocate`'s "Move/Copy/Nothing" action plus the target wiki
         * path). Defaults to `null` so the standard CLI-driven flow is
         * unaffected.
         */
        val freeformInput: FreeformInput? = null,
    )

    data class Option(
        val label: String,
        val description: String,
    )

    /**
     * Optional editable text field rendered alongside the radio/checkbox
     * options for a question.
     *
     * @property prefill the initial value of the input
     * @property label optional `<label>` text shown above the input
     * @property placeholder HTML `placeholder`; falls back to [prefill]
     */
    data class FreeformInput(
        val prefill: String,
        val label: String? = null,
        val placeholder: String? = null,
    )

    companion object {
        private val gson = Gson()

        /**
         * Parse the raw `input` JSON string from the MCP request_permission call.
         * Returns null if the JSON is malformed or doesn't look like an
         * AskUserQuestion payload.
         */
        fun parse(json: String): AskUserQuestionInput? {
            if (json.isBlank()) return null
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonObject) return null
                val obj = root.asJsonObject
                val questionsArr = obj.get("questions")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: return null
                val questions = questionsArr.mapNotNull { qElem ->
                    if (!qElem.isJsonObject) return@mapNotNull null
                    val q = qElem.asJsonObject
                    val text = q.getString("question") ?: return@mapNotNull null
                    val header = q.getString("header") ?: ""
                    val multi = q.get("multiSelect")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    val opts = q.get("options")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.mapNotNull { oElem ->
                            if (!oElem.isJsonObject) return@mapNotNull null
                            val o = oElem.asJsonObject
                            val label = o.getString("label") ?: return@mapNotNull null
                            val description = o.getString("description") ?: ""
                            Option(label, description)
                        } ?: emptyList()
                    val freeform = parseFreeformInput(q.get("freeformInput"))
                    Question(text, header, opts, multi, freeform)
                }
                if (questions.isEmpty()) null else AskUserQuestionInput(questions)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Build the `updatedInput` JSON the CLI expects on allow: the original
         * input with an `answers` field merged in. Each entry in [answers] maps
         * a question text to a single label (single-select) or a comma-separated
         * list of labels (multi-select).
         *
         * Original fields (questions, metadata, annotations, ...) are preserved
         * so the tool receives them on the second pass.
         */
        fun buildUpdatedInput(originalInputJson: String, answers: Map<String, String>): String {
            val obj: JsonObject = try {
                val parsed = JsonParser.parseString(originalInputJson.ifBlank { "{}" })
                if (parsed.isJsonObject) parsed.asJsonObject else JsonObject()
            } catch (_: Exception) {
                JsonObject()
            }
            val answersObj = JsonObject()
            for ((questionText, label) in answers) {
                if (questionText.isBlank() || label.isBlank()) continue
                answersObj.addProperty(questionText, label)
            }
            obj.add("answers", answersObj)
            return gson.toJson(obj)
        }

        private fun JsonObject.getString(key: String): String? {
            val el = this.get(key) ?: return null
            return if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString else null
        }

        private fun parseFreeformInput(el: com.google.gson.JsonElement?): FreeformInput? {
            if (el == null || !el.isJsonObject) return null
            return try {
                val obj = el.asJsonObject
                val prefill = obj.getString("prefill") ?: return null
                val label = obj.getString("label")
                val placeholder = obj.getString("placeholder")
                FreeformInput(prefill, label, placeholder)
            } catch (_: Exception) {
                null
            }
        }
    }
}
