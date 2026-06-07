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
package com.adobe.clawdea.knowledge

import com.adobe.clawdea.CLAWDEA_DIR
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One-time, idempotent relocation of plugin-generated artifacts out of the legacy
 * `<project>/<claudeDirName>/` location (default `.claude/`) into ClawDEA's own
 * `<project>/.clawdea/` directory. Runs silently on project open.
 *
 * Moves `REPO_STATE.md` and (in default mode) the `wiki/` directory — both
 * ClawDEA-owned and historically written under `.claude/`. Claude Code's own
 * files (notes, skills) stay where they are. Team-mode projects (with
 * `.clawdea/config.json`) keep their configured wiki path untouched.
 *
 * `SIBLINGS.md` is no longer written by the plugin (the primer renders the
 * siblings view in-memory); [removeStaleSiblings] deletes any leftover copy
 * from older versions.
 */
object ClawdeaArtifactMigrator {

    private val LOG = Logger.getInstance(ClawdeaArtifactMigrator::class.java)

    private val FILE_NAMES = listOf("REPO_STATE.md")

    /**
     * @return number of files relocated (0 when there is nothing to migrate).
     */
    fun migrate(projectRoot: Path, claudeDirName: String): Int {
        val legacyDir = projectRoot.resolve(claudeDirName)
        val targetDir = projectRoot.resolve(CLAWDEA_DIR)
        var moved = 0
        for (name in FILE_NAMES) {
            val legacy = legacyDir.resolve(name)
            if (!Files.isRegularFile(legacy)) continue
            val target = targetDir.resolve(name)
            try {
                if (Files.exists(target)) {
                    // A current copy already lives in .clawdea/ — the legacy file is
                    // stale; drop it so we don't keep two divergent versions.
                    Files.deleteIfExists(legacy)
                    LOG.info("Removed stale legacy $name from $legacyDir (current copy exists in $targetDir)")
                    continue
                }
                Files.createDirectories(targetDir)
                try {
                    Files.move(legacy, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(legacy, target)
                }
                moved++
                LOG.info("Migrated $name: $legacy -> $target")
            } catch (e: Throwable) {
                LOG.warn("Failed to migrate $name from $legacy to $target", e)
            }
        }
        return moved
    }

    /**
     * Deletes any leftover `SIBLINGS.md` — from the legacy `<claudeDirName>/`
     * location and from `.clawdea/` — written by older plugin versions. The
     * file is no longer produced (the primer renders the siblings view in
     * memory), so a lingering copy is just stale noise. Idempotent.
     *
     * @return number of files removed.
     */
    fun removeStaleSiblings(projectRoot: Path, claudeDirName: String): Int {
        val candidates = listOf(
            projectRoot.resolve(claudeDirName).resolve("SIBLINGS.md"),
            projectRoot.resolve(CLAWDEA_DIR).resolve("SIBLINGS.md"),
        )
        var removed = 0
        for (file in candidates) {
            if (!Files.isRegularFile(file)) continue
            try {
                Files.deleteIfExists(file)
                removed++
                LOG.info("Removed stale SIBLINGS.md: $file")
            } catch (e: Throwable) {
                LOG.warn("Failed to remove stale SIBLINGS.md at $file", e)
            }
        }
        return removed
    }

    /**
     * Relocates the **default-mode** wiki tree from `<claudeDirName>/<wikiSubdir>`
     * (legacy `.claude/wiki/`) to `.clawdea/<wikiSubdir>`. No-op when:
     *  - the project is in team mode (`.clawdea/config.json` present) — its wiki
     *    lives at the configured path and must not be touched;
     *  - there is no legacy wiki to move;
     *  - a wiki already exists at the new location (avoid clobbering / two wikis —
     *    the stale legacy tree is removed if it has no files left).
     *
     * @return true when the tree was moved.
     */
    fun migrateWikiDir(projectRoot: Path, claudeDirName: String, wikiSubdir: String): Boolean {
        if (Files.isRegularFile(projectRoot.resolve(CLAWDEA_DIR).resolve("config.json"))) return false
        val legacy = projectRoot.resolve(claudeDirName).resolve(wikiSubdir)
        if (!Files.isDirectory(legacy)) return false
        val target = projectRoot.resolve(CLAWDEA_DIR).resolve(wikiSubdir)
        if (Files.exists(target)) {
            // New wiki already present — don't merge two trees. Drop the legacy
            // copy if it has no files (best-effort) so it doesn't linger.
            if (!hasRegularFiles(legacy)) deleteEmptyTree(legacy)
            return false
        }
        return try {
            Files.createDirectories(target.parent)
            moveTree(legacy, target)
            LOG.info("Migrated wiki: $legacy -> $target")
            true
        } catch (e: Throwable) {
            LOG.warn("Failed to migrate wiki from $legacy to $target", e)
            false
        }
    }

    /** Moves every file under [src] to the matching path under [dst], then removes empty [src] dirs. */
    private fun moveTree(src: Path, dst: Path) {
        val files = Files.walk(src).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
        for (file in files) {
            val rel = src.relativize(file)
            val out = dst.resolve(rel.toString())
            Files.createDirectories(out.parent)
            try {
                Files.move(file, out, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(file, out, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        deleteEmptyTree(src)
    }

    private fun hasRegularFiles(dir: Path): Boolean =
        Files.walk(dir).use { s -> s.anyMatch { Files.isRegularFile(it) } }

    /** Removes [dir] and every directory beneath it, bottom-up. Best-effort; only deletes empty dirs. */
    private fun deleteEmptyTree(dir: Path) {
        if (!Files.isDirectory(dir)) return
        val dirs = Files.walk(dir).use { s -> s.filter { Files.isDirectory(it) }.toList() }
        for (d in dirs.sortedByDescending { it.nameCount }) {
            try {
                val empty = Files.list(d).use { !it.findAny().isPresent }
                if (empty) Files.deleteIfExists(d)
            } catch (_: Exception) { /* best-effort */ }
        }
    }
}
