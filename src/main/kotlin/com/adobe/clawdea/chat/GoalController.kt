/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.chat

/**
 * Tracks the state of an active `/goal` so the chat panel can render a banner.
 * The CLI owns the goal loop; this is a pure, JCEF-free mirror driven by the
 * forwarded command ([onSet]/[onClear]) and the stream ([onFeedback]/[onResult]),
 * unit-testable in the headless `./gradlew test` subset.
 */
class GoalController {

    data class GoalState(
        val condition: String,
        val turnCount: Int = 0,
        val latestReason: String = "",
        val achieved: Boolean = false,
    )

    private var state: GoalState? = null

    /** A `/goal <condition>` was issued — begin (or replace) the active goal. */
    fun onSet(condition: String) {
        state = GoalState(condition = condition)
    }

    /**
     * A Stop-hook evaluation arrived. Increments the turn count and records the
     * reason. Self-activates if no goal was set locally (e.g. a goal restored on
     * `--resume`, where ClawDEA never saw the original `/goal` command).
     */
    fun onFeedback(condition: String, reason: String) {
        val cur = state
        state = if (cur == null) {
            GoalState(condition = condition, turnCount = 1, latestReason = reason)
        } else {
            cur.copy(
                condition = cur.condition.ifBlank { condition },
                turnCount = cur.turnCount + 1,
                latestReason = reason,
            )
        }
    }

    /**
     * The turn loop ended with a `result`. If a goal is active, it was met —
     * return the achieved final state once and deactivate. Returns null when no
     * goal is active (ordinary turn end).
     */
    fun onResult(): GoalState? {
        val cur = state ?: return null
        state = null
        return cur.copy(achieved = true)
    }

    fun onClear() {
        state = null
    }

    fun current(): GoalState? = state
}
