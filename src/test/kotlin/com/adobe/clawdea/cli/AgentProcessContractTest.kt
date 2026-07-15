package com.adobe.clawdea.cli

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProcessContractTest {
    @Test
    fun `CliProcess is an AgentProcess`() {
        val process = CliProcess(workingDirectory = "/tmp")
        assertTrue(process is AgentProcess)
    }

    @Test
    fun `Claude backend does not support native steer`() {
        val process = CliProcess(workingDirectory = "/tmp")
        assertFalse(process.supportsSteer)
        assertFalse(process.steer("anything"))
    }
}
