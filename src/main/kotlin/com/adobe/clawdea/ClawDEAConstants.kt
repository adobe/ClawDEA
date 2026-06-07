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
package com.adobe.clawdea

/**
 * Plugin-wide constants. Keep this file small; only true cross-cutting values
 * belong here. Anything that's owned by a single subsystem should live there.
 */

/**
 * The Claude Code config directory name, used both as `~/.claude/` (user-global,
 * fixed by Claude Code) and as `<project>/.claude/` (project-local, user-overridable
 * via `ClawDEASettings.claudeDirName`). The string is the same in both contexts; the
 * constant exists to dedupe the literal across call sites.
 */
const val CLAUDE_DIR = ".claude"

/**
 * ClawDEA's own per-project directory (`<project>/.clawdea/`). Holds plugin-owned
 * artifacts that are not part of Claude Code's `~/.claude/` contract: the team-mode
 * `config.json`, per-user wiki state (`wiki-state.local.json`), the default-mode
 * wiki (`wiki/`), and the generated `REPO_STATE.md`. Fixed (not user-overridable)
 * so the layout is stable across clones and teammates. Unlike [CLAUDE_DIR], this is
 * never used for the user-global `~/.clawdea/` path beyond plugin caches.
 */
const val CLAWDEA_DIR = ".clawdea"
