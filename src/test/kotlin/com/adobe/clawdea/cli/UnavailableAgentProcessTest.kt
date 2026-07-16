package com.adobe.clawdea.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnavailableAgentProcessTest {
    @Test
    fun `start emits one normal error result and exits`() {
        val process = UnavailableAgentProcess("HTTP agent backend is not available")

        process.start()
        assertTrue(process.isAlive)

        val event = CliEventParser().parse(process.readLine()!!)
        assertTrue(event is CliEvent.Result)
        event as CliEvent.Result
        assertEquals("HTTP agent backend is not available", event.text)
        assertTrue(event.isError)
        assertFalse(process.isAlive)
    }
}
