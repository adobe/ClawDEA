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
package com.adobe.clawdea.commands.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class WikiRelocateHandlerTest {

    @Test fun `validatePath rejects absolute paths`() {
        val err = WikiRelocateHandler.validatePath("/abs/path")
        assertNotNull(err)
    }

    @Test fun `validatePath rejects parent traversal`() {
        val err = WikiRelocateHandler.validatePath("../escape")
        assertNotNull(err)
    }

    @Test fun `validatePath rejects empty input`() {
        val err = WikiRelocateHandler.validatePath("")
        assertNotNull(err)
    }

    @Test fun `validatePath accepts a relative subpath`() {
        assertNull(WikiRelocateHandler.validatePath("docs/llm-wiki"))
    }

    @Test fun `writeConfig creates clawdea config json with wikiPath`() {
        val tmp = Files.createTempDirectory("relocate-cfg")
        try {
            WikiRelocateHandler.writeConfig(projectBase = tmp, wikiPath = "docs/llm-wiki")
            val content = Files.readString(tmp.resolve(".clawdea").resolve("config.json"))
            assertEquals("""{"wikiPath":"docs/llm-wiki"}""", content)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `appendGitignore creates file with entry when missing`() {
        val tmp = Files.createTempDirectory("relocate-gi-new")
        try {
            WikiRelocateHandler.appendGitignore(tmp, ".clawdea/wiki-state.local.json")
            val content = Files.readString(tmp.resolve(".gitignore"))
            assertEquals(".clawdea/wiki-state.local.json\n", content)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `appendGitignore appends entry to existing file`() {
        val tmp = Files.createTempDirectory("relocate-gi-add")
        try {
            Files.writeString(tmp.resolve(".gitignore"), "build/\n")
            WikiRelocateHandler.appendGitignore(tmp, ".clawdea/wiki-state.local.json")
            val content = Files.readString(tmp.resolve(".gitignore"))
            assertEquals("build/\n.clawdea/wiki-state.local.json\n", content)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `appendGitignore is idempotent (no duplicate entry)`() {
        val tmp = Files.createTempDirectory("relocate-gi-dup")
        try {
            Files.writeString(tmp.resolve(".gitignore"), "build/\n.clawdea/wiki-state.local.json\n")
            WikiRelocateHandler.appendGitignore(tmp, ".clawdea/wiki-state.local.json")
            val content = Files.readString(tmp.resolve(".gitignore"))
            assertEquals("build/\n.clawdea/wiki-state.local.json\n", content)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
