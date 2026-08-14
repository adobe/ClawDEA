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
 * The single hand-rolled JSON primitive shared by the NDJSON hot path (CliEventParser) and the MCP
 * JSON-RPC layer (McpProtocol). This deliberately does **not** use Gson: the CLI emits hundreds of
 * partial-message events per second under `--include-partial-messages`, and reflection-based
 * deserialization regressed allocation hotspots in past profiles. Everything here is char-scanning
 * over the input string, returning `substring` slices — no intermediate objects, no reflection.
 *
 * It consolidates ~17 copy-pasted extractors that had drifted into three incompatible escape
 * behaviors, and fixes two bugs those copies carried (Tier 4.1):
 *  - [unescape] now decodes `\uXXXX` (and `\b` / `\f`); the old copies dropped them, re-emitting a
 *    non-ASCII escape as its literal six characters.
 *  - [escape] now emits `\u00xx` for every control character U+0000–U+001F, as RFC 8259 requires;
 *    the old copy escaped only `\ " \n \r \t`, so an ESC byte in source produced invalid JSON that
 *    the CLI rejected wholesale, silently breaking search_text / find_symbol / get_diagnostics.
 *
 * The string-aware [findBalancedEnd] scan (a `}`/`]` inside a quoted value must not close the span)
 * is verified-correct and must not be "simplified".
 */
object FastJson {

    private const val FORM_FEED = '\u000C'

    /** RFC 8259 string-body escaping (no surrounding quotes). Escapes control chars as `\u00xx`. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                FORM_FEED -> sb.append("\\f")
                else ->
                    if (c < ' ') {
                        sb.append("\\u")
                            .append(HEX[(c.code shr 12) and 0xF])
                            .append(HEX[(c.code shr 8) and 0xF])
                            .append(HEX[(c.code shr 4) and 0xF])
                            .append(HEX[c.code and 0xF])
                    } else {
                        sb.append(c)
                    }
            }
        }
        return sb.toString()
    }

    /**
     * Decode a JSON string body (the text between the quotes) into its literal characters. Handles
     * `\" \\ \/ \n \r \t \b \f` and `\uXXXX` (each `\u` unit appended as a UTF-16 char, so a
     * surrogate pair reconstructs naturally). An incomplete/invalid escape is passed through
     * literally, matching the lenient behavior of the parsers this replaces.
     */
    fun unescape(s: String): String {
        // Fast path: no escapes at all.
        if (s.indexOf('\\') == -1) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append(FORM_FEED)
                    'u' -> {
                        val code = if (i + 6 <= s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                            continue
                        }
                        sb.append('\\').append('u')
                    }
                    else -> { sb.append('\\'); sb.append(next) }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * The raw JSON number token for [key] — the run of number characters after the colon — or null
     * if the key, its colon, or a number is absent. [key] is matched literally (callers include the
     * surrounding quotes if they want an exact key match). Callers apply their own
     * `toIntOrNull` / `toLongOrNull` / `toDoubleOrNull` and default, so per-site numeric type and
     * fallback (`0` vs `0.0` vs `null`) are preserved.
     */
    fun numberToken(json: String, key: String): String? {
        val keyIndex = json.indexOf(key)
        if (keyIndex == -1) return null
        val colonIndex = json.indexOf(':', keyIndex + key.length)
        if (colonIndex == -1) return null
        var i = colonIndex + 1
        while (i < json.length && json[i].isWhitespace()) i++
        val start = i
        while (i < json.length) {
            val c = json[i]
            if (c.isDigit() || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++ else break
        }
        return if (i > start) json.substring(start, i) else null
    }

    /**
     * Index of the closing quote of a JSON string whose body starts at [from] (i.e. [from] is the
     * first char after the opening quote). Escape-aware: `\"` does not terminate. Returns null if
     * unterminated.
     */
    fun findStringEnd(s: String, from: Int): Int? {
        var i = from
        while (i < s.length) {
            when {
                s[i] == '\\' && i + 1 < s.length -> i += 2
                s[i] == '"' -> return i
                else -> i++
            }
        }
        return null
    }

    /**
     * Index of the [close] char that balances the [open] char at index [from]. String-aware: an
     * [open]/[close] inside a quoted value is ignored. Returns null if unbalanced. This is the
     * verified-correct scan behind extractNestedObject / nested-value capture — do not simplify.
     */
    fun findBalancedEnd(s: String, from: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = from
        var inString = false
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (c == '\\' && i + 1 < s.length) { i += 2; continue }
                if (c == '"') inString = false
                i++
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
        }
        return null
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
