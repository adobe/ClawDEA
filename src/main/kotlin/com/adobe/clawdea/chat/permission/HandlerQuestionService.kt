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
 * looking up the requestId in the pending map (see [owns]) so a single
 * [com.intellij.ui.jcef.JBCefJSQuery] bridge can serve both kinds of card
 * without parsing payload contents to disambiguate.
 *
 * Lifecycle:
 *  - [register] generates a requestId and parks the resolver. Caller passes
 *    that id to [AskUserQuestionRenderer.renderCard] to produce the HTML.
 *  - On JCEF submit, [PermissionRequestHandler] sees [owns] return true and
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

    /**
     * Returns true if [requestId] is currently registered with this service.
     *
     * We consult the pending map directly rather than just checking the
     * cosmetic [HANDLER_QUESTION_PREFIX] — that way a CLI permission request
     * whose id happens to start with the prefix can never be misrouted, and
     * we avoid double-routing the same submit (the id is removed from the
     * map on the first [submit]/[cancel]).
     */
    override fun owns(requestId: String): Boolean = pending.containsKey(requestId)

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
        /**
         * Cosmetic id prefix — purely for log readability and to make
         * handler-question ids visually distinguishable from CLI permission
         * ids. Routing is keyed on the pending map (see [owns]); the prefix
         * does NOT participate in dispatch.
         *
         * Must NOT contain a colon: the JCEF bridge dispatches
         * `"<requestId>:<action>:<data>"` and parses with `split(":", limit=3)`.
         * A colon in the prefix would make the JS-side requestId split across
         * `parts[0]` and `parts[1]`, dropping the action entirely (regression
         * fixed in this module's history — clicking Submit silently no-oped).
         */
        const val HANDLER_QUESTION_PREFIX = "hq-"
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
