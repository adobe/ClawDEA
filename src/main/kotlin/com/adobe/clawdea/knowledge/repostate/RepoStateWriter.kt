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
package com.adobe.clawdea.knowledge.repostate

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object RepoStateWriter {

    private val LOG = Logger.getInstance(RepoStateWriter::class.java)

    fun write(projectRoot: Path, claudeDirName: String, content: String) {
        val dir = projectRoot.resolve(claudeDirName)
        Files.createDirectories(dir)
        val target = dir.resolve("REPO_STATE.md")
        val temp = Files.createTempFile(dir, "REPO_STATE.md.tmp", "")
        try {
            Files.writeString(temp, content)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (Files.exists(temp)) {
                try { Files.deleteIfExists(temp) } catch (_: Exception) {}
            }
        }
        LOG.info("Wrote REPO_STATE.md (${content.length} bytes) to $target")
    }
}
