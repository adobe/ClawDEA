/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.chat

/**
 * Tracks in-flight sub-agent dispatches (the CLI `Task` tool) so the
 * [EventStreamHandler] can group a sub-agent's inner events under one
 * collapsible card. Pure state + query logic — no JCEF — so it is unit-testable
 * in the headless `./gradlew test` subset.
 *
 * NOTE: "SubAgent" deliberately does NOT reuse the `Task*` vocabulary, which in
 * this codebase means TodoWrite todos ([TaskWidgetController], [CliEvent.TaskEvent]).
 */
class SubAgentController {

    enum class Status { RUNNING, DONE, ERROR, ABORTED }

    data class SubAgentState(
        val id: String,
        val agentType: String,
        val description: String,
        val startTimeMs: Long,
        var stepCount: Int = 0,
        var status: Status = Status.RUNNING,
        /**
         * True once the CLI has reported this dispatch as a background
         * (`run_in_background`) agent via a `system/task_started` event. A
         * background card must NOT be finalized on its launch-ack `ToolResult`
         * (its inner steps stream afterward) nor swept at the turn `Result` (it
         * outlives the turn). It is closed authoritatively by the matching
         * `system/task_notification`. See [EventStreamHandler]'s BackgroundTask
         * handling.
         */
        var launchedInBackground: Boolean = false,
    )

    private val active = LinkedHashMap<String, SubAgentState>()

    /** Register a dispatch. [id] is the `Task` tool_use id (the card id). */
    fun register(id: String, agentType: String, description: String, nowMs: Long): SubAgentState {
        val state = SubAgentState(id, agentType, description, nowMs)
        active[id] = state
        return state
    }

    fun isActive(id: String): Boolean = active.containsKey(id)

    /** Dispatch start time for an active card, or null if unknown. */
    fun startTimeMs(id: String): Long? = active[id]?.startTimeMs

    /**
     * The card id a child event should render into, or null when the event
     * belongs at top level. A non-null [parentToolUseId] that is not currently
     * active (deeper nesting / unknown) also returns null — the depth-1 fallback.
     */
    fun parentCardFor(parentToolUseId: String?): String? =
        if (parentToolUseId != null && active.containsKey(parentToolUseId)) parentToolUseId else null

    /** Increment the step counter for [cardId]; returns the new count, or -1 if unknown. */
    fun recordStep(cardId: String): Int {
        val state = active[cardId] ?: return -1
        state.stepCount += 1
        return state.stepCount
    }

    /**
     * Mark [id]'s dispatch as a background launch (its [ToolResult] was a launch
     * ack, not a completion). No-op if [id] isn't active. Returns true if marked.
     */
    fun markLaunchedInBackground(id: String): Boolean {
        val state = active[id] ?: return false
        state.launchedInBackground = true
        return true
    }

    /** True if [id] is active and was marked as a background launch. */
    fun isLaunchedInBackground(id: String): Boolean = active[id]?.launchedInBackground == true

    /** Remove [id], stamp [status], and return its final state (or null if not active). */
    fun finalize(id: String, status: Status): SubAgentState? {
        val state = active.remove(id) ?: return null
        state.status = status
        return state
    }

    fun activeIds(): List<String> = active.keys.toList()

    fun isEmpty(): Boolean = active.isEmpty()

    companion object {
        /**
         * Tool names the CLI uses to dispatch a sub-agent. Confirmed against a
         * captured sandbox session — the dispatch tool is named `Agent` (NOT
         * `Task`, which is the TodoWrite todo widget in this codebase).
         */
        val SUBAGENT_TOOLS = setOf("Agent")

        fun isSubAgentTool(name: String): Boolean = name in SUBAGENT_TOOLS
    }
}
