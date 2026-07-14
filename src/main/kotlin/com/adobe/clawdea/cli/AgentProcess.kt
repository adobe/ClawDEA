package com.adobe.clawdea.cli

import com.adobe.clawdea.skills.SkillInfo

/**
 * A running agentic CLI subprocess. Implemented by [CliProcess] (Claude Code)
 * and, from Phase 2, CodexProcess (OpenAI). The contract is deliberately the
 * subset of [CliProcess] that [CliBridge]'s reader loop depends on, so the
 * bridge can drive either backend without knowing which CLI is behind it.
 */
interface AgentProcess {
    val isAlive: Boolean
    fun start(resumeSessionId: String? = null, skills: List<SkillInfo> = emptyList())
    fun readLine(): String?
    fun writeLine(json: String)
    fun sendInterrupt()
    fun stop()
    fun recentStderrLines(): List<String>
}
