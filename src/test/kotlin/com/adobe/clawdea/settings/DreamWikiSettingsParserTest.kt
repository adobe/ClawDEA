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
package com.adobe.clawdea.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DreamWikiSettingsParserTest {

    @Test fun `min elapsed hours rejects blank non numeric and negative values`() {
        assertEquals(24, DreamWikiSettingsParser.minElapsedHours(""))
        assertEquals(24, DreamWikiSettingsParser.minElapsedHours("abc"))
        assertEquals(24, DreamWikiSettingsParser.minElapsedHours("-1"))
        assertEquals(0, DreamWikiSettingsParser.minElapsedHours("0"))
        assertEquals(48, DreamWikiSettingsParser.minElapsedHours("48"))
    }

    @Test fun `min signal units allows zero and positive values`() {
        assertEquals(5, DreamWikiSettingsParser.minSignalUnits(""))
        assertEquals(5, DreamWikiSettingsParser.minSignalUnits("abc"))
        assertEquals(5, DreamWikiSettingsParser.minSignalUnits("-1"))
        assertEquals(0, DreamWikiSettingsParser.minSignalUnits("0"))
        assertEquals(7, DreamWikiSettingsParser.minSignalUnits("7"))
    }

    @Test fun `scan throttle minutes rejects blank non numeric and negative values`() {
        assertEquals(10, DreamWikiSettingsParser.scanThrottleMinutes(""))
        assertEquals(10, DreamWikiSettingsParser.scanThrottleMinutes("abc"))
        assertEquals(10, DreamWikiSettingsParser.scanThrottleMinutes("-1"))
        assertEquals(0, DreamWikiSettingsParser.scanThrottleMinutes("0"))
        assertEquals(20, DreamWikiSettingsParser.scanThrottleMinutes("20"))
    }
}
