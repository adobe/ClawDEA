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
package com.adobe.clawdea.knowledge.primer.sources

import org.junit.Assert.assertTrue
import org.junit.Test

class WikiIndexSourceTest {
    @Test
    fun `directive asks for standard markdown wiki links`() {
        val directive = WikiIndexSource.buildDirective(".claude/wiki", autoUpdate = false)

        assertTrue(directive.contains("Use standard Markdown links between wiki pages"))
        assertTrue(directive.contains("do not create new `[[concept]]` references"))
    }

    @Test
    fun `auto-update gap action asks for markdown index links`() {
        val directive = WikiIndexSource.buildDirective(".claude/wiki", autoUpdate = true)

        assertTrue(directive.contains("[Title](concepts/<slug>.md)"))
    }

    @Test
    fun `reviewed gap action preserves propose tools`() {
        val directive = WikiIndexSource.buildDirective(".claude/wiki", autoUpdate = false)

        assertTrue(directive.contains("`propose_write`"))
        assertTrue(directive.contains("`propose_edit`"))
        assertTrue(directive.contains("[Title](concepts/<slug>.md)"))
    }
}
