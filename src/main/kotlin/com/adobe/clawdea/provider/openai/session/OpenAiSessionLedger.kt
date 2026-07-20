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
package com.adobe.clawdea.provider.openai.session

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Append-only JSONL ledger for OpenAI-compatible chat sessions. Each session is stored as
 * `~/.clawdea/sessions/<sha256(project-path)>/<session-id>.jsonl`.
 *
 * - **Recovery contract**: stops at the first malformed line and returns all prior valid records.
 * - **Concurrency**: per-session locks ensure safe concurrent appends.
 * - **Permissions**: directories created with owner-only perms where supported (posix).
 */
class OpenAiSessionLedger(
    private val projectPath: String,
    private val baseDir: Path = defaultBaseDir(),
) {

    private val gson = Gson()
    private val sessionLocks = ConcurrentHashMap<String, ReentrantLock>()

    companion object {
        private fun defaultBaseDir(): Path {
            val home = System.getProperty("user.home")
            return File(home, ".clawdea/sessions").toPath()
        }

        /**
         * Compute the per-project subdir name: lowercase hex sha256 of the canonical project path.
         */
        internal fun projectDirName(canonicalProjectPath: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalProjectPath.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * Read a ledger file, applying the recovery contract: skip blank lines; stop at the first
         * malformed line and return all prior valid records.
         */
        fun read(path: Path): LoadedLedger {
            if (!Files.exists(path)) {
                return LoadedLedger(emptyList(), trailingRecordCorrupt = false)
            }

            val records = mutableListOf<SessionLedgerRecord>()
            var corrupt = false

            try {
                Files.newBufferedReader(path).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            val record = try {
                                Gson().fromJson(line, SessionLedgerRecord::class.java)
                            } catch (_: Exception) {
                                corrupt = true
                                break
                            }
                            records.add(record)
                        }
                        line = reader.readLine()
                    }
                }
            } catch (_: Exception) {
                // Best-effort: return what we parsed
            }

            return LoadedLedger(records, corrupt)
        }
    }

    /**
     * Append a record to the session ledger. Creates directories/file as needed, with owner-only
     * permissions on posix. Flushes before returning.
     */
    fun append(sessionId: String, record: SessionLedgerRecord) {
        val lock = sessionLocks.computeIfAbsent(sessionId) { ReentrantLock() }
        lock.withLock {
            val file = ledgerFile(sessionId)
            ensureLedgerDir(file.parent)

            val json = gson.toJson(record)
            Files.write(
                file,
                (json + "\n").toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    private fun ledgerFile(sessionId: String): Path {
        val canonicalProjectPath = File(projectPath).canonicalPath
        val projectSubdir = projectDirName(canonicalProjectPath)
        return baseDir.resolve(projectSubdir).resolve("$sessionId.jsonl")
    }

    private fun ensureLedgerDir(dir: Path) {
        if (Files.exists(dir)) return

        try {
            // Posix: owner-only perms (rwx------)
            val attrs = try {
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
            } catch (_: UnsupportedOperationException) {
                null
            }

            if (attrs != null) {
                Files.createDirectories(dir, attrs)
            } else {
                Files.createDirectories(dir)
                // Non-posix: best-effort via File.setReadable/Writable
                dir.toFile().setReadable(true, true)
                dir.toFile().setWritable(true, true)
                dir.toFile().setExecutable(true, true)
            }
        } catch (_: Exception) {
            // Fallback: create without explicit perms
            Files.createDirectories(dir)
        }
    }
}

/**
 * Result of reading a ledger file.
 *
 * @param records all valid records before any corrupt line
 * @param trailingRecordCorrupt true if the file ended with a malformed line (the recovery contract)
 */
data class LoadedLedger(
    val records: List<SessionLedgerRecord>,
    val trailingRecordCorrupt: Boolean,
)
