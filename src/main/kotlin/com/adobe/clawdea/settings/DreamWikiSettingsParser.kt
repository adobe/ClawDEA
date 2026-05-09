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

internal object DreamWikiSettingsParser {
    const val MIN_ELAPSED_HOURS_DEFAULT = 24
    const val MIN_SIGNAL_UNITS_DEFAULT = 5
    const val SCAN_THROTTLE_MINUTES_DEFAULT = 10

    fun minElapsedHours(text: String): Int =
        parse(text, defaultValue = MIN_ELAPSED_HOURS_DEFAULT, allowZero = true)

    fun minSignalUnits(text: String): Int =
        parse(text, defaultValue = MIN_SIGNAL_UNITS_DEFAULT, allowZero = true)

    fun scanThrottleMinutes(text: String): Int =
        parse(text, defaultValue = SCAN_THROTTLE_MINUTES_DEFAULT, allowZero = true)

    private fun parse(text: String, defaultValue: Int, allowZero: Boolean): Int {
        val value = text.trim().toIntOrNull() ?: return defaultValue
        return if (value > 0 || (allowZero && value == 0)) value else defaultValue
    }
}
