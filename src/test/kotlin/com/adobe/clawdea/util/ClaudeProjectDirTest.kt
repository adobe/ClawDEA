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
import org.junit.Assert.assertFalse
import org.junit.Test

class ClaudeProjectDirTest {

    @Test fun `posix path without special chars matches the legacy scheme`() {
        assertEquals(
            "-Users-alice-Work-aem-ClawDEA",
            ClaudeProjectDir.encode("/Users/alice/Work/aem/ClawDEA"),
        )
    }

    @Test fun `dotted segment becomes a dash like the CLI double-dashes _claude worktrees`() {
        // Real on-disk dir the CLI created: the `/.` collapses to `--`.
        assertEquals(
            "-Users-me-Work-ClawDEA--claude-worktrees-x",
            ClaudeProjectDir.encode("/Users/me/Work/ClawDEA/.claude/worktrees/x"),
        )
    }

    @Test fun `underscores and dots both map to dash`() {
        // Verified against the CLI: /private/tmp/enc.test_dir -> -private-tmp-enc-test-dir
        assertEquals(
            "-private-tmp-enc-test-dir",
            ClaudeProjectDir.encode("/private/tmp/enc.test_dir"),
        )
    }

    @Test fun `windows path with drive colon and backslashes produces a colon-free name`() {
        // The 2026-07 crash was InvalidPathException "Illegal char <:>" from `-C:-Users-…`:
        // the drive colon survived the old `/`-only replace and broke Path.resolve.
        val encoded = ClaudeProjectDir.encode("""C:\Users\Matei\TheActualDocuments\SocialApp""")
        assertFalse("drive colon must not survive (breaks Path.resolve)", encoded.contains(':'))
        assertFalse("backslash must not survive", encoded.contains('\\'))
        // `C:\` → `C--` (both the colon and the separator map to a dash).
        assertEquals("-C--Users-Matei-TheActualDocuments-SocialApp", encoded)
    }

    @Test fun `windows path as intellij reports it (forward slashes) is also colon-free`() {
        // IntelliJ's project.basePath uses forward slashes even on Windows, so the real input
        // is `C:/Users/…`; the drive colon is still the only illegal char to neutralize.
        val encoded = ClaudeProjectDir.encode("C:/Users/Matei/TheActualDocuments/SocialApp")
        assertFalse(encoded.contains(':'))
        assertEquals("-C--Users-Matei-TheActualDocuments-SocialApp", encoded)
    }

    @Test fun `result always starts with a single leading dash`() {
        assertEquals("-p", ClaudeProjectDir.encode("/p"))
        assertEquals("-p", ClaudeProjectDir.encode("p"))
    }
}
