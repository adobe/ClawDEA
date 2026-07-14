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
}
