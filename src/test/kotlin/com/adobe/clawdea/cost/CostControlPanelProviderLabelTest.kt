package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Test

class CostControlPanelProviderLabelTest {
    @Test
    fun `provider titles come from the registry`() {
        assertEquals("Amazon Bedrock", CostControlPanel.providerTitle("bedrock"))
        assertEquals("OpenAI-compatible", CostControlPanel.providerTitle("openai-compatible"))
    }
}
