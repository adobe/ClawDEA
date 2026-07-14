package com.adobe.clawdea.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCatalogTest {
    @Test
    fun `default catalogs include a non-empty openai list`() {
        val map = defaultModelCatalogsMap()
        assertTrue(map.containsKey("openai"))
        assertFalse(map["openai"]!!.isEmpty())
    }

    @Test
    fun `openai catalog ids look like gpt models`() {
        assertTrue(DEFAULT_OPENAI_CATALOG.all { it.id.isNotBlank() && it.displayName.isNotBlank() })
        assertTrue(DEFAULT_OPENAI_CATALOG.any { it.id.startsWith("gpt-") })
    }

    @Test
    fun `openai-subscription defers to the account default with an empty static catalog`() {
        // A ChatGPT account rejects the API model IDs (HTTP 400); the eligible set is
        // per-account and populated by the live probe. The key must exist (so the dropdown
        // renders the working "Default" entry) but the static catalog is intentionally empty.
        val map = defaultModelCatalogsMap()
        assertTrue(map.containsKey("openai-subscription"))
        assertTrue(map["openai-subscription"]!!.isEmpty())
    }
}
