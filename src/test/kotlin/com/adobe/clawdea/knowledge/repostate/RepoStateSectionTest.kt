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
package com.adobe.clawdea.knowledge.repostate

import org.junit.Assert.assertEquals
import org.junit.Test

class RepoStateSectionTest {

    @Test
    fun `format renders heading and body`() {
        val section = RepoStateSection(heading = "Modules", body = "- bundles/acme-modules-api\n- bundles/acme-modules-core")
        assertEquals(
            "## Modules\n\n- bundles/acme-modules-api\n- bundles/acme-modules-core\n",
            section.format()
        )
    }

    @Test
    fun `format trims trailing whitespace from body`() {
        val section = RepoStateSection(heading = "Modules", body = "- foo\n\n   ")
        assertEquals("## Modules\n\n- foo\n", section.format())
    }
}
