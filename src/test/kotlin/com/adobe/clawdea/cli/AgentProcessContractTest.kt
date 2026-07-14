package com.adobe.clawdea.cli

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProcessContractTest {
    @Test
    fun `CliProcess is an AgentProcess`() {
        val process = CliProcess(workingDirectory = "/tmp")
        assertTrue(process is AgentProcess)
    }
}
