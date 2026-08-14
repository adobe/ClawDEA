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

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Crash-safe file writes. Replaces four verbatim write-to-temp-then-move copies (Tier 4.5). */
object AtomicFiles {

    /**
     * Write [content] to [target] via a sibling temp file and an atomic rename, so a reader never
     * sees a half-written file and a crash mid-write cannot corrupt the previous contents. Falls
     * back to a plain replace on filesystems without atomic move, and always cleans up the temp.
     */
    fun writeAtomically(target: Path, content: String) {
        val parent = target.parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, target.fileName.toString() + ".tmp", "")
        try {
            Files.writeString(temp, content)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (Files.exists(temp)) {
                try { Files.deleteIfExists(temp) } catch (_: Exception) {}
            }
        }
    }
}
