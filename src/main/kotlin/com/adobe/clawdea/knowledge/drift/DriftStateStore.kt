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

import com.adobe.clawdea.settings.ClawDEASettings
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads/writes `DriftState`. Mode-aware:
 *  - Default mode: single file `<wikiDir>/.drift-state.json` (back-compat).
 *  - Team mode: team-shared `<wikiDir>/.wiki-state.json` (lastSyncedCommit, suggestions)
 *    + per-user `<projectBase>/.clawdea/wiki-state.local.json` (everything else).
 *
 * Mode is detected by `<projectBase>/.clawdea/config.json` presence.
 *
 * On first read in team mode (when no team file exists yet), legacy
 * `<projectBase>/<claudeDirName>/<wikiSubdir>/.drift-state.json` is split into the
 * new files and deleted. Migration is idempotent (only runs when the team file is
 * absent).
 */
object DriftStateStore {

    private val LOG = Logger.getInstance(DriftStateStore::class.java)
    private val GSON = Gson()

    // One lock per logical state (keyed by wikiDir + projectBase) so all
    // read-modify-write cycles on the same `.wiki-state.json` / `.drift-state.json`
    // serialize. Without this, concurrent writers (the periodic rescan and the
    // chat-session wiki-librarian both mutate `suggestions`) can lose each other's
    // updates between their separate read and write. Interned so every caller
    // targeting the same files shares the same monitor.
    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(wikiDir: Path, projectBase: Path?): Any {
        val key = wikiDir.toAbsolutePath().normalize().toString() +
            "\u0000" + (projectBase?.toAbsolutePath()?.normalize()?.toString() ?: "")
        return locks.computeIfAbsent(key) { Any() }
    }
    private const val LEGACY_FILE = ".drift-state.json"
    private const val TEAM_FILE = ".wiki-state.json"
    private const val PER_USER_FILE = "wiki-state.local.json"
    private const val CLAWDEA_DIR = ".clawdea"
    private const val DEFAULT_CLAUDE_DIR = ".claude"
    private const val DEFAULT_WIKI_SUBDIR = "wiki"

    /**
     * Default-mode entry point retained for callers that have not yet been
     * updated. Internally checks for team mode via `projectBase` discovery.
     */
    fun read(wikiDir: Path): DriftState = read(wikiDir = wikiDir, projectBase = null)

    fun read(wikiDir: Path, projectBase: Path?): DriftState {
        if (projectBase == null) return readDefault(wikiDir)
        val configFile = projectBase.resolve(CLAWDEA_DIR).resolve("config.json")
        return if (Files.isRegularFile(configFile)) readTeam(wikiDir, projectBase) else readDefault(wikiDir)
    }

    fun write(wikiDir: Path, state: DriftState) = write(wikiDir = wikiDir, projectBase = null, state = state)

    fun write(wikiDir: Path, projectBase: Path?, state: DriftState) {
        if (projectBase == null) { writeDefault(wikiDir, state); return }
        val configFile = projectBase.resolve(CLAWDEA_DIR).resolve("config.json")
        if (Files.isRegularFile(configFile)) writeTeam(wikiDir, projectBase, state) else writeDefault(wikiDir, state)
    }

    fun update(wikiDir: Path, projectBase: Path?, transform: (DriftState) -> DriftState) {
        mutate(wikiDir, projectBase) { transform(it) to Unit }
    }

    /** Legacy single-arg update kept for unmodified callers. */
    fun update(wikiDir: Path, transform: (DriftState) -> DriftState) {
        update(wikiDir = wikiDir, projectBase = null, transform = transform)
    }

    /**
     * Atomic read-modify-write under the per-state lock: reads the current state,
     * applies [transform] (which returns the new state plus a caller result), and
     * persists it — all while holding [lockFor], so no concurrent writer can slip
     * a write between this read and write. Use for any mutation whose decision
     * depends on the current on-disk state (e.g. recording a suggestion, dismissing
     * a signature, advancing the synced commit).
     */
    fun <T> mutate(wikiDir: Path, projectBase: Path?, transform: (DriftState) -> Pair<DriftState, T>): T =
        synchronized(lockFor(wikiDir, projectBase)) {
            val (newState, result) = transform(read(wikiDir, projectBase))
            write(wikiDir, projectBase, newState)
            result
        }

    // ---- Default mode (single file) ----

    private fun readDefault(wikiDir: Path): DriftState {
        val file = wikiDir.resolve(LEGACY_FILE)
        if (!Files.isRegularFile(file)) return DriftState()
        return try {
            GSON.fromJson(Files.readString(file), DriftState::class.java) ?: DriftState()
        } catch (e: Throwable) {
            LOG.warn("Failed to read drift state from $file: ${e.message}")
            DriftState()
        }
    }

    private fun writeDefault(wikiDir: Path, state: DriftState) {
        Files.createDirectories(wikiDir)
        atomicWrite(wikiDir.resolve(LEGACY_FILE), GSON.toJson(state))
    }

    // ---- Team mode (split files + one-time migration) ----

    private fun readTeam(wikiDir: Path, projectBase: Path): DriftState {
        val teamFile = wikiDir.resolve(TEAM_FILE)
        val perUserFile = projectBase.resolve(CLAWDEA_DIR).resolve(PER_USER_FILE)

        // Migration: if the team file is absent, attempt to populate from legacy.
        if (!Files.isRegularFile(teamFile)) {
            migrateFromLegacy(projectBase, wikiDir)
        }

        val team = readTeamPart(teamFile)
        val perUser = readPerUserPart(perUserFile)
        return DriftState(
            lastScanAt = perUser.lastScanAt,
            lastSyncedCommit = team.lastSyncedCommit,
            dismissed = perUser.dismissed,
            probeMisses = perUser.probeMisses,
            userCorrections = perUser.userCorrections,
            suggestions = team.suggestions,
        )
    }

    private fun writeTeam(wikiDir: Path, projectBase: Path, state: DriftState) {
        Files.createDirectories(wikiDir)
        Files.createDirectories(projectBase.resolve(CLAWDEA_DIR))
        val team = TeamPart(state.lastSyncedCommit, state.suggestions)
        val perUser = PerUserPart(state.lastScanAt, state.dismissed, state.probeMisses, state.userCorrections)
        atomicWrite(wikiDir.resolve(TEAM_FILE), GSON.toJson(team))
        atomicWrite(projectBase.resolve(CLAWDEA_DIR).resolve(PER_USER_FILE), GSON.toJson(perUser))
    }

    private fun migrateFromLegacy(projectBase: Path, newWikiDir: Path) {
        // Fall back to the defaults declared on ClawDEASettings.State when no
        // IntelliJ Application is registered (unit tests, early init). The vast
        // majority of installs use these defaults anyway.
        val (claudeDirName, wikiSubdir) = try {
            val s = ClawDEASettings.getInstance().state
            s.claudeDirName to s.wikiSubdir
        } catch (_: Throwable) {
            DEFAULT_CLAUDE_DIR to DEFAULT_WIKI_SUBDIR
        }
        // Check both possible legacy locations: the conventional .claude/wiki/
        // and the team-mode wiki dir itself (for projects that ran in default
        // mode at a custom wiki path before opting into team mode).
        val candidates = listOf(
            projectBase.resolve(claudeDirName).resolve(wikiSubdir).resolve(LEGACY_FILE),
            newWikiDir.resolve(LEGACY_FILE),
        ).filter { Files.isRegularFile(it) }
        val legacyFile = candidates.firstOrNull() ?: return
        val legacyState = try {
            GSON.fromJson(Files.readString(legacyFile), DriftState::class.java) ?: DriftState()
        } catch (e: Throwable) {
            LOG.warn("Migration: failed to read legacy drift-state at $legacyFile: ${e.message}")
            return
        }
        // Write split files first, then delete the legacy file(s). Idempotent —
        // if anything fails before delete, the next read retries.
        writeTeam(newWikiDir, projectBase, legacyState)
        for (file in candidates) {
            try { Files.deleteIfExists(file) } catch (e: Throwable) {
                LOG.warn("Migration: failed to delete legacy file $file: ${e.message}")
            }
        }
    }

    private fun readTeamPart(file: Path): TeamPart {
        if (!Files.isRegularFile(file)) return TeamPart()
        return try {
            GSON.fromJson(Files.readString(file), TeamPart::class.java) ?: TeamPart()
        } catch (e: Throwable) {
            LOG.warn("Failed to read team drift state from $file: ${e.message}")
            TeamPart()
        }
    }

    private fun readPerUserPart(file: Path): PerUserPart {
        if (!Files.isRegularFile(file)) return PerUserPart()
        return try {
            GSON.fromJson(Files.readString(file), PerUserPart::class.java) ?: PerUserPart()
        } catch (e: Throwable) {
            LOG.warn("Failed to read per-user drift state from $file: ${e.message}")
            PerUserPart()
        }
    }

    private fun atomicWrite(target: Path, content: String) {
        val parent = target.parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, target.fileName.toString() + ".tmp", "")
        try {
            Files.writeString(temp, content)
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

    private data class TeamPart(
        val lastSyncedCommit: String = "",
        val suggestions: List<DriftEvent.WikiSuggestion> = emptyList(),
    )

    private data class PerUserPart(
        val lastScanAt: String = "",
        val dismissed: List<String> = emptyList(),
        val probeMisses: List<ProbeMiss> = emptyList(),
        val userCorrections: List<UserCorrectionRecord> = emptyList(),
    )
}
