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
    )

    data class Option(
        val label: String,
        val description: String,
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
                    Question(text, header, opts, multi)
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
    }
}
