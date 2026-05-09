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
package com.adobe.clawdea.actions

enum class ActionType(
    val isCodeAction: Boolean,
) {
    EXPLAIN(isCodeAction = false),
    OPTIMIZE(isCodeAction = true),
    GENERATE_TEST(isCodeAction = true),
    SECURITY_CHECK(isCodeAction = false),
    ADD_DOCUMENTATION(isCodeAction = true),
    REFACTOR(isCodeAction = true),
    ASK_CLAUDE(isCodeAction = false),
    FIX(isCodeAction = true),
}

class ActionPromptBuilder {

    data class ActionPrompt(
        val systemPrompt: String,
        val userMessage: String,
    )

    fun build(
        actionType: ActionType,
        selectedText: String,
        contextSnippet: String,
        userInstructions: String?,
    ): ActionPrompt {
        val systemPrompt = buildString {
            append(getSystemInstructions(actionType))
            if (contextSnippet.isNotBlank()) {
                append("\n\n<context>\n")
                append(contextSnippet)
                append("\n</context>")
            }
        }

        val userMessage = buildString {
            when (actionType) {
                ActionType.REFACTOR -> {
                    append("Refactor the following code according to these instructions: ")
                    append(userInstructions ?: "")
                    append("\n\n```\n")
                    append(selectedText)
                    append("\n```")
                }
                ActionType.ASK_CLAUDE -> {
                    append(userInstructions ?: "What does this code do?")
                    append("\n\n```\n")
                    append(selectedText)
                    append("\n```")
                }
                ActionType.FIX -> {
                    append("Fix the following issue: ")
                    append(userInstructions ?: "")
                    append("\n\nCode:\n```\n")
                    append(selectedText)
                    append("\n```")
                }
                else -> {
                    append("```\n")
                    append(selectedText)
                    append("\n```")
                }
            }
        }

        return ActionPrompt(systemPrompt, userMessage)
    }

    private fun getSystemInstructions(actionType: ActionType): String = when (actionType) {
        ActionType.EXPLAIN ->
            "Explain the following code clearly and concisely. " +
            "Focus on what it does, why it's structured this way, and any non-obvious behavior. " +
            "Use markdown formatting."

        ActionType.OPTIMIZE ->
            "Provide an optimized version of the following code. " +
            "Preserve the same behavior and API contract. " +
            "Output ONLY the replacement code — no explanation, no code fences, no markdown."

        ActionType.GENERATE_TEST ->
            "Generate a comprehensive unit test for the following code. " +
            "Cover the main behavior plus edge cases. " +
            "Use the same test framework and style conventions as the surrounding project. " +
            "Output ONLY the replacement code — no explanation, no code fences, no markdown."

        ActionType.SECURITY_CHECK ->
            "Analyze the following code for security vulnerabilities. " +
            "Check for OWASP Top 10 issues: injection, broken auth, sensitive data exposure, " +
            "XXE, broken access control, misconfiguration, XSS, deserialization, " +
            "known vulnerabilities, and insufficient logging. " +
            "Use markdown formatting. Be specific about each issue found and how to fix it."

        ActionType.ADD_DOCUMENTATION ->
            "Add documentation to the following code. " +
            "Use the language's standard documentation format (Javadoc for Java, KDoc for Kotlin, etc.). " +
            "Document parameters, return values, and any thrown exceptions. " +
            "Output ONLY the replacement code with documentation added — no explanation, no code fences, no markdown."

        ActionType.REFACTOR ->
            "Refactor the following code according to the user's instructions. " +
            "Preserve the same external behavior. " +
            "Output ONLY the replacement code — no explanation, no code fences, no markdown."

        ActionType.ASK_CLAUDE ->
            "Answer the user's question about the following code. " +
            "Be clear and concise. Use markdown formatting."

        ActionType.FIX ->
            "Fix the issue described in the diagnostic message. " +
            "Output ONLY the replacement code — no explanation, no code fences, no markdown."
    }
}
