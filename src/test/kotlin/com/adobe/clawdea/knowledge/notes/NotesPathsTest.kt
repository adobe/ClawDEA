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
package com.adobe.clawdea.knowledge.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class NotesPathsTest {
    @Test fun `notesDirFor encodes project basePath the same way SessionScanner does`() {
        val home = Paths.get("/home/alice")
        val dir = NotesPaths.notesDirFor(home, "/Users/alice/Work/aem/ClawDEA")
        assertEquals(Paths.get("/home/alice/.claude/projects/-Users-alice-Work-aem-ClawDEA/notes"), dir)
    }

    @Test fun `currentMdFor returns CURRENT_md inside the notes dir`() {
        val home = Paths.get("/home/alice")
        val current = NotesPaths.currentMdFor(home, "/Users/alice/Work/aem/ClawDEA")
        assertTrue(current.toString().endsWith("/notes/CURRENT.md"))
    }

    @Test fun `resolveNoteFile accepts a simple relative name`() {
        val home = Paths.get("/home/alice")
        val resolved = NotesPaths.resolveNoteFile(home, "/p", "CURRENT.md")
        assertEquals(NotesPaths.currentMdFor(home, "/p"), resolved)
    }

    @Test fun `resolveNoteFile rejects path traversal via dot dot`() {
        val home = Paths.get("/home/alice")
        assertNull(NotesPaths.resolveNoteFile(home, "/p", "../escape.md"))
    }

    @Test fun `resolveNoteFile rejects absolute paths outside notes dir`() {
        val home = Paths.get("/home/alice")
        assertNull(NotesPaths.resolveNoteFile(home, "/p", "/etc/passwd"))
    }

    @Test fun `resolveNoteFile rejects backslash separators`() {
        val home = Paths.get("/home/alice")
        assertNull(NotesPaths.resolveNoteFile(home, "/p", "..\\escape.md"))
    }

    @Test fun `resolveNoteFile accepts a subdir under notes dir`() {
        val home = Paths.get("/home/alice")
        val resolved = NotesPaths.resolveNoteFile(home, "/p", "archive/2026-04.md")
        assertTrue(resolved!!.startsWith(NotesPaths.notesDirFor(home, "/p")))
        assertTrue(resolved.toString().endsWith("/archive/2026-04.md"))
    }

    @Test fun `resolveNoteFile rejects empty argument`() {
        val home = Paths.get("/home/alice")
        assertNull(NotesPaths.resolveNoteFile(home, "/p", ""))
    }
}
