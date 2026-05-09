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
package com.adobe.clawdea.cli

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays a recorded NDJSON turn through CliEventParser and fails if any line
 * parses to a CliEvent.Unknown whose rawType isn't in the known-ignored set.
 *
 * Acts as the PR-time half of the Claude Code compat-regression tripwire — see
 * docs/superpowers/specs/2026-04-29-claude-code-drift-monitoring-design.md.
 *
 * The fixture is intended to be refreshed weekly by a scheduled GitHub Action
 * (see #119); when a refresh introduces a new rawType not in the ignored set,
 * this test fails and the failure annotates the exact event type that drifted.
 *
 * The KNOWN_IGNORED set documents Claude Code event shapes that ClawDEA
 * deliberately does not model today. Each entry is a deferred Tier-1 work item;
 * if a type leaves the CLI's emission set, drop it here.
 */
class CliFixtureReplayTest {

    @Test
    fun `every event in latest fixture is recognized or in the ignored set`() {
        val resource = javaClass.getResourceAsStream("/cli-fixtures/latest.ndjson")
        requireNotNull(resource) {
            "latest.ndjson fixture missing — capture one with `claude -p --output-format stream-json " +
                "--input-format stream-json --verbose --include-partial-messages` and place it under " +
                "src/test/resources/cli-fixtures/"
        }
        val lines = resource.bufferedReader().readLines().filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "latest.ndjson is empty" }

        val parser = CliEventParser()
        val unexpected = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            val event = parser.parse(line)
            if (event is CliEvent.Unknown && event.rawType !in KNOWN_IGNORED_RAW_TYPES) {
                unexpected += "line ${i + 1}: rawType=\"${event.rawType}\""
            }
        }

        val message = "Unrecognized events in latest.ndjson — drift suspected:\n" +
            unexpected.joinToString("\n") +
            "\n\nIf the new rawType is genuinely safe to ignore, add it to KNOWN_IGNORED_RAW_TYPES " +
            "with a follow-up issue. Otherwise, teach CliEventParser to recognize it."
        assertTrue(message, unexpected.isEmpty())
    }

    companion object {
        // rawTypes the parser intentionally returns Unknown for. Each is a
        // deferred work item — see issues filed against the umbrella tracker.
        private val KNOWN_IGNORED_RAW_TYPES = setOf(
            // Runtime telemetry that ClawDEA does not surface today.
            "rate_limit_event",
            // stream_event sub-shapes other than content_block_delta/text_delta —
            // CliEventParser only models text deltas; message_start, content_block_start,
            // content_block_stop, message_delta, message_stop all return Unknown by design.
            "stream_event",
        )
    }
}
