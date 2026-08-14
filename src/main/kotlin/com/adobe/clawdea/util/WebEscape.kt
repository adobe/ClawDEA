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

/**
 * The single home for HTML- and JS-string escaping, replacing six drifted `escapeHtml` copies (some
 * missing `'`, some missing `"`) and five per-call-site JS schemes — one of which, an inverted
 * `replace("'", …).replace("\\", …)`, doubled the backslash it had just inserted and broke the JS
 * string literal (Tier 4.4).
 */
object WebEscape {

    /**
     * Escape text for insertion into HTML body/attribute content. Escapes all five significant
     * entities (`& < > " '`). When [preserveNewlines] is true a newline is emitted as `&#10;` so it
     * survives an attribute value (the WikiGitStateBanner case); otherwise it passes through.
     */
    fun html(s: String, preserveNewlines: Boolean = false): String = buildString(s.length + 16) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                '\n' -> if (preserveNewlines) append("&#10;") else append(c)
                else -> append(c)
            }
        }
    }

    /** Escape a value for a single-quoted JavaScript string literal. */
    fun jsSingleQuoted(s: String): String = jsBody(s, '\'')

    /** Escape a value for a double-quoted JavaScript string literal. */
    fun jsDoubleQuoted(s: String): String = jsBody(s, '"')

    // Backslash MUST be escaped first (a later pass would otherwise double a backslash this pass
    // inserts). U+2028/U+2029 are line terminators *inside* JS string literals, so an un-escaped one
    // silently breaks the injected script — hence they are escaped even though they are not control
    // characters.
    private fun jsBody(s: String, quote: Char): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                quote -> append('\\').append(quote)
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(c)
            }
        }
    }
}
