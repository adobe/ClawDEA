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
 * Encodes an absolute project path into the folder name the Claude Code CLI uses under
 * `~/.claude/projects/<encoded>/`. This is the single source of truth for that scheme;
 * [com.adobe.clawdea.chat.session.SessionScanner],
 * [com.adobe.clawdea.cost.TranscriptCostReader], and
 * [com.adobe.clawdea.knowledge.notes.NotesPaths] all route through it so the plugin points
 * at the exact directory the CLI creates.
 *
 * The CLI replaces **every** non-alphanumeric character with `-` (verified against real
 * on-disk dirs: `/Users/me/Work/ClawDEA/.claude/worktrees/x` →
 * `-Users-me-Work-ClawDEA--claude-worktrees-x`, and `/private/tmp/enc.test_dir` →
 * `-private-tmp-enc-test-dir`). The earlier implementation only replaced `/`, which:
 *   - could not locate sessions for any path containing `.` or `_` (e.g. `.claude` worktrees), and
 *   - crashed on Windows, where the drive colon (`C:\Users\…`) survived into a
 *     [java.nio.file.Path] and threw `InvalidPathException: Illegal char <:>`.
 *
 * Replacing the full non-alphanumeric class is a superset of the old `/`-only behavior for
 * POSIX paths without special characters, so it reproduces the existing directory names
 * while also handling backslashes, drive colons, and dotted segments.
 */
object ClaudeProjectDir {

    private val NON_ALNUM = Regex("[^a-zA-Z0-9]")

    /** The `~/.claude/projects/<encoded>` folder name for [projectBasePath] (leading `-` included). */
    fun encode(projectBasePath: String): String = "-" + NON_ALNUM.replace(projectBasePath, "-").trimStart('-')
}
