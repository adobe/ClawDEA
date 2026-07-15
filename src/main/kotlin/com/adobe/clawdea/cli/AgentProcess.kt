package com.adobe.clawdea.cli

import com.adobe.clawdea.skills.SkillInfo

/**
 * A running agentic CLI subprocess. Implemented by [CliProcess] (Claude Code)
 * and [CodexAppServerProcess] (OpenAI `codex app-server`). The contract is
 * deliberately the subset of [CliProcess] that [CliBridge]'s reader loop depends
 * on, so the bridge can drive either backend without knowing which CLI is behind it.
 */
interface AgentProcess {
    val isAlive: Boolean
    fun start(resumeSessionId: String? = null, skills: List<SkillInfo> = emptyList())
    fun readLine(): String?
    fun writeLine(line: String)
    fun sendInterrupt()
    fun stop()
    fun recentStderrLines(): List<String>

    /**
     * True if this backend can inject a message into the *currently-running* turn
     * (native mid-turn steering). Claude Code has no such primitive (SIGINT ends the
     * turn), so it defaults to false; [CodexAppServerProcess] overrides it (`turn/steer`).
     */
    val supportsSteer: Boolean get() = false

    /**
     * Injects [text] into the active turn without interrupting it (native steer). Returns
     * true when a turn was live and the steer was dispatched; false when there was no
     * steerable turn, in which case the caller should fall back to a normal new turn.
     */
    fun steer(text: String): Boolean = false
}
