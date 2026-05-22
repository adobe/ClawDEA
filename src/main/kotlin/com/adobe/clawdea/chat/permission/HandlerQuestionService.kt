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

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds resolution callbacks for handler-initiated AskUserQuestion cards.
 * The card is reused from the CLI permission flow; routing is done by
 * requestId prefix ([HANDLER_QUESTION_PREFIX]) so a single
 * [com.intellij.ui.jcef.JBCefJSQuery] bridge can serve both kinds of card
 * without parsing payload contents to disambiguate.
 *
 * Lifecycle:
 *  - [register] generates a requestId and parks the resolver. Caller passes
 *    that id to [AskUserQuestionRenderer.renderCard] to produce the HTML.
 *  - On JCEF submit, [PermissionRequestHandler] sees the prefix matches and
 *    forwards to [submit] (or [cancel] for Skip). The resolver fires once
 *    and is removed.
 *  - If the user navigates away or the panel disposes before submitting,
 *    the resolver is leaked. That's acceptable for /wiki-relocate (the user
 *    can re-run it) and avoids tracking project-lifecycle handles for
 *    short-lived cards.
 */
@Service(Service.Level.PROJECT)
class HandlerQuestionService(@Suppress("unused") private val project: Project? = null) :
    PermissionRequestHandler.HandlerQuestionResolver {

    private val pending = ConcurrentHashMap<String, (HandlerQuestionAnswers?) -> Unit>()

    /** Register [onResolve] under a fresh requestId. Caller renders a card with this id. */
    fun register(onResolve: (HandlerQuestionAnswers?) -> Unit): String {
        val id = HANDLER_QUESTION_PREFIX + UUID.randomUUID()
        pending[id] = onResolve
        return id
    }

    /** Returns true if [requestId] was issued by this service. */
    override fun owns(requestId: String): Boolean = requestId.startsWith(HANDLER_QUESTION_PREFIX)

    override fun submit(requestId: String, answers: Map<String, String>, freeforms: Map<String, String>) {
        val resolver = pending.remove(requestId) ?: run {
            LOG.debug("submit: unknown requestId $requestId")
            return
        }
        try {
            resolver(HandlerQuestionAnswers(answers, freeforms))
        } catch (t: Throwable) {
            LOG.warn("HandlerQuestion submit resolver threw", t)
        }
    }

    override fun cancel(requestId: String) {
        val resolver = pending.remove(requestId) ?: return
        try {
            resolver(null)
        } catch (t: Throwable) {
            LOG.warn("HandlerQuestion cancel resolver threw", t)
        }
    }

    companion object {
        const val HANDLER_QUESTION_PREFIX = "hq:"
        private val LOG = Logger.getInstance(HandlerQuestionService::class.java)
        fun getInstance(project: Project): HandlerQuestionService =
            project.getService(HandlerQuestionService::class.java)
    }
}

/**
 * Result of an answered handler-question card. `answers` maps each question
 * text to the selected option label; `freeforms` maps the same question text
 * to the trimmed value the user typed into the optional freeform input
 * (empty map if the question had no freeform field).
 */
data class HandlerQuestionAnswers(
    val answers: Map<String, String>,
    val freeforms: Map<String, String>,
)
