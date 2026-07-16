/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.provider.openai.client

import com.adobe.clawdea.gateway.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiChatSseParserTest {

    @Test
    fun `parser emits content and stop`() {
        val parser = OpenAiChatSseParser()
        assertEquals(
            StreamEvent.TextDelta("hel"),
            parser.parseLine("""data: {"choices":[{"delta":{"content":"hel"}}]}"""),
        )
        assertEquals(StreamEvent.MessageStop(null), parser.parseLine("data: [DONE]"))
    }

    @Test
    fun `parser emits error event`() {
        val parser = OpenAiChatSseParser()
        assertEquals(
            StreamEvent.Error("rate limit exceeded"),
            parser.parseLine("""data: {"error":{"message":"rate limit exceeded"}}"""),
        )
    }
}
