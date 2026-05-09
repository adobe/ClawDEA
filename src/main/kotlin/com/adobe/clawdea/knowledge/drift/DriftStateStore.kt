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
package com.adobe.clawdea.knowledge.drift

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object DriftStateStore {

    private val LOG = Logger.getInstance(DriftStateStore::class.java)
    private val GSON = Gson()
    private const val FILE_NAME = ".drift-state.json"
    private const val WIKI_SUBDIR = "wiki"

    fun read(claudeDir: Path): DriftState {
        val file = claudeDir.resolve(WIKI_SUBDIR).resolve(FILE_NAME)
        if (!Files.isRegularFile(file)) return DriftState()
        return try {
            val text = Files.readString(file)
            GSON.fromJson(text, DriftState::class.java) ?: DriftState()
        } catch (e: Throwable) {
            LOG.warn("Failed to read drift state from $file: ${e.message}")
            DriftState()
        }
    }

    fun write(claudeDir: Path, state: DriftState) {
        val dir = claudeDir.resolve(WIKI_SUBDIR)
        Files.createDirectories(dir)
        val target = dir.resolve(FILE_NAME)
        val temp = Files.createTempFile(dir, ".drift-state.json.tmp", "")
        try {
            Files.writeString(temp, GSON.toJson(state))
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (Files.exists(temp)) {
                try { Files.deleteIfExists(temp) } catch (_: Exception) {}
            }
        }
    }

    fun update(claudeDir: Path, transform: (DriftState) -> DriftState) {
        val current = read(claudeDir)
        val next = transform(current)
        write(claudeDir, next)
    }
}
