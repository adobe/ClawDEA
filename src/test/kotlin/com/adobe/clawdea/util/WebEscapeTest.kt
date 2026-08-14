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
package com.adobe.clawdea.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WebEscapeTest {

    @Test
    fun `html escapes all five significant entities`() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", WebEscape.html("&<>\"'"))
    }

    @Test
    fun `html passes newlines through by default and encodes them when asked`() {
        assertEquals("a\nb", WebEscape.html("a\nb"))
        assertEquals("a&#10;b", WebEscape.html("a\nb", preserveNewlines = true))
    }

    @Test
    fun `jsSingleQuoted escapes backslash first, then the single quote`() {
        // The bug this replaces did the two replaces in the wrong order: a lone ' became \\' and
        // terminated the JS string. Correct output is \' with the backslash escaped exactly once.
        assertEquals("it\\'s", WebEscape.jsSingleQuoted("it's"))
        assertEquals("a\\\\b", WebEscape.jsSingleQuoted("a\\b"))
        // A backslash followed by a quote must not collapse: \' stays two escapes.
        assertEquals("\\\\\\'", WebEscape.jsSingleQuoted("\\'"))
    }

    @Test
    fun `jsDoubleQuoted escapes backslash and the double quote`() {
        assertEquals("say \\\"hi\\\"", WebEscape.jsDoubleQuoted("say \"hi\""))
        assertEquals("a\\\\b", WebEscape.jsDoubleQuoted("a\\b"))
    }

    @Test
    fun `js escaping covers newlines and the JS line separators U+2028 and U+2029`() {
        assertEquals("a\\nb\\r", WebEscape.jsSingleQuoted("a\nb\r"))
        assertEquals("\\u2028\\u2029", WebEscape.jsSingleQuoted("\u2028\u2029"))
        assertEquals("\\u2028\\u2029", WebEscape.jsDoubleQuoted("\u2028\u2029"))
    }
}
