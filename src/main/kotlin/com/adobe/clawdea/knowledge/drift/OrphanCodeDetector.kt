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

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Coverage detector for greenfield subsystems the wiki has never described.
 *
 * [CommitWikiDriftDetector] and [CodeRenameDetector] can only fire off tokens
 * that some *existing* wiki page already mentions — they detect "an existing
 * page was invalidated", never "a whole new area landed with no page". A
 * brand-new subsystem dropped into existing packages (e.g. the `Codex*`
 * classes added across `auth/`, `cli/`, `cost/`, `gateway/`) is invisible to
 * both: no page references any of its files, so every intersection is empty.
 *
 * This detector closes that blind spot. It clusters declared source types by
 * their leading PascalCase word ("prefix"), and emits a
 * [DriftEvent.OrphanSubsystem] for each cluster of at least [MIN_CLUSTER_SIZE]
 * types where the wiki mentions **neither** any of the cluster's class names
 * **nor** the prefix word itself. The two-gate test keeps false positives out:
 * a subsystem whose prefix word appears anywhere in prose (e.g. "profiling",
 * "process") is considered documented at the concept level even if the exact
 * class names don't appear.
 *
 * Both gates operate on a lowercased whole-word token set of the wiki, so a
 * class name embedded in a longer identifier does not count as a mention.
 *
 * The pure core [computeOrphans] is fully unit-tested; the filesystem-walking
 * [detect] wrapper is a thin adapter over it.
 */
object OrphanCodeDetector {

    private val LOG = Logger.getInstance(OrphanCodeDetector::class.java)

    /** Clusters smaller than this are too small to be worth a concept page. */
    const val MIN_CLUSTER_SIZE = 3

    /** Cap the paths listed per event so the wiki-author digest stays readable. */
    const val MAX_PATHS_PER_EVENT = 5

    /**
     * Prefixes that are structural noise rather than subsystem names — generic
     * suffix-style role words that cluster across unrelated packages. Excluded
     * so we never suggest "document the Handler subsystem".
     */
    private val PREFIX_DENYLIST = setOf("handler", "abstract", "default", "base", "simple", "test")

    private val KT_DECL_RX = Regex(
        """^\s*(?:public|private|internal|open|sealed|abstract|final|data)?\s*(?:class|object|interface|enum\s+class)\s+([A-Z][A-Za-z0-9_]+)""",
        RegexOption.MULTILINE,
    )

    /** Leading PascalCase word of a type name: `CodexAppServerProcess` -> `Codex`. */
    private val PREFIX_RX = Regex("""^[A-Z][a-z0-9]+""")

    /** Whole-word tokens for wiki mention lookup. */
    private val WORD_RX = Regex("""[A-Za-z0-9]+""")

    /**
     * A discovered source type and the repo-relative path it was declared in.
     */
    data class SourceType(val name: String, val path: String)

    /**
     * Walk [sourceRoots] for `.kt`/`.java` files, walk [wikiDir] for `.md`
     * pages, and defer to [computeOrphans]. Any IO failure degrades to no
     * events rather than throwing into the rescan.
     */
    fun detect(wikiDir: Path, sourceRoots: List<Path>): List<DriftEvent.OrphanSubsystem> {
        if (!Files.isDirectory(wikiDir)) return emptyList()
        val types = try {
            collectSourceTypes(sourceRoots)
        } catch (e: Throwable) {
            LOG.warn("OrphanCodeDetector source walk failed: ${e.message}")
            return emptyList()
        }
        if (types.isEmpty()) return emptyList()
        val wikiText = try {
            readWikiText(wikiDir)
        } catch (e: Throwable) {
            LOG.warn("OrphanCodeDetector wiki walk failed: ${e.message}")
            return emptyList()
        }
        return computeOrphans(types, wikiText)
    }

    /**
     * Pure core. Given the declared source types and the concatenated wiki
     * text, cluster by prefix and emit an event per uncovered cluster.
     */
    fun computeOrphans(types: List<SourceType>, wikiText: String): List<DriftEvent.OrphanSubsystem> {
        val wikiWords = WORD_RX.findAll(wikiText).map { it.value.lowercase() }.toHashSet()

        // Group types by leading PascalCase word.
        val byPrefix = LinkedHashMap<String, MutableList<SourceType>>()
        for (type in types) {
            val prefix = PREFIX_RX.find(type.name)?.value ?: continue
            byPrefix.getOrPut(prefix) { mutableListOf() }.add(type)
        }

        val out = mutableListOf<DriftEvent.OrphanSubsystem>()
        for ((prefix, cluster) in byPrefix) {
            // Dedup class names — the same type can appear under multiple roots
            // (e.g. src/main/kotlin and bin/main) or be declared once but read twice.
            val distinctNames = cluster.map { it.name }.distinct()
            if (distinctNames.size < MIN_CLUSTER_SIZE) continue
            if (prefix.lowercase() in PREFIX_DENYLIST) continue

            // Gate 1: no class name in the cluster appears as a whole word in the wiki.
            val anyClassMentioned = distinctNames.any { it.lowercase() in wikiWords }
            if (anyClassMentioned) continue
            // Gate 2: the prefix word itself does not appear in the wiki.
            if (prefix.lowercase() in wikiWords) continue

            val paths = cluster.map { it.path }.distinct().sorted().take(MAX_PATHS_PER_EVENT)
            out += DriftEvent.OrphanSubsystem(
                prefix = prefix,
                classNames = distinctNames.sorted(),
                representativePaths = paths,
            )
        }
        return out
    }

    private fun collectSourceTypes(sourceRoots: List<Path>): List<SourceType> {
        val out = mutableListOf<SourceType>()
        for (root in sourceRoots) {
            if (!Files.isDirectory(root)) continue
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && isSourceFile(it) }
                    .forEach { file ->
                        val text = runCatching { Files.readString(file) }.getOrNull() ?: return@forEach
                        val rel = relativePath(root, file)
                        KT_DECL_RX.findAll(text).forEach { m ->
                            out += SourceType(name = m.groupValues[1], path = rel)
                        }
                    }
            }
        }
        return out
    }

    private fun isSourceFile(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        return name.endsWith(".kt") || name.endsWith(".java")
    }

    /**
     * Repo-relative path for display in the digest. [root] is a source root
     * like `<repo>/src/main/kotlin`; we want the path relative to the repo, so
     * we walk up two known segments when present, else fall back to the leaf.
     */
    private fun relativePath(root: Path, file: Path): String {
        // Prefer a `src/`-anchored path so the digest matches how other events
        // and the wiki refer to files.
        val full = file.toString().replace('\\', '/')
        val idx = full.indexOf("/src/")
        if (idx >= 0) return full.substring(idx + 1)
        return runCatching { root.relativize(file).toString().replace('\\', '/') }
            .getOrDefault(file.fileName?.toString() ?: full)
    }

    private fun readWikiText(wikiDir: Path): String {
        val sb = StringBuilder()
        Files.walk(wikiDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName?.toString()?.endsWith(".md") == true }
                .forEach { page ->
                    runCatching { Files.readString(page) }.getOrNull()?.let {
                        sb.append(it).append('\n')
                    }
                }
        }
        return sb.toString()
    }
}
