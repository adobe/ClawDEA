package com.adobe.clawdea.settings

import org.junit.Assert.assertNotNull
import org.junit.Test

class OpenAiKeyAttrTest {
    @Test
    fun `settings exposes openai key accessors`() {
        val method = ClawDEASettings::class.java.getMethod("getOpenAIApiKey")
        assertNotNull(method)
        val setter = ClawDEASettings::class.java.getMethod("setOpenAIApiKey", String::class.java)
        assertNotNull(setter)
    }
}
