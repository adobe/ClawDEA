package com.adobe.clawdea.cli

import com.adobe.clawdea.skills.SkillInfo
import com.google.gson.Gson
import java.util.concurrent.LinkedBlockingQueue

class UnavailableAgentProcess(private val reason: String) : AgentProcess {
    private val output = LinkedBlockingQueue<String>()

    @Volatile
    private var alive = false

    override val isAlive: Boolean get() = alive

    override fun start(resumeSessionId: String?, skills: List<SkillInfo>) {
        alive = true
        output.put("""{"type":"result","subtype":"error","is_error":true,"result":${Gson().toJson(reason)},"session_id":""}""")
    }

    override fun readLine(): String? = output.take().also { alive = false }
    override fun writeLine(line: String) = Unit
    override fun sendInterrupt() {
        alive = false
    }

    override fun stop() {
        alive = false
    }

    override fun recentStderrLines(): List<String> = emptyList()
}
