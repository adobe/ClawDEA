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
package com.adobe.clawdea.knowledge.wiki

import java.nio.file.Files

data class WikiSearchHit(
    val relativePath: String,
    val matchCount: Int,
    val firstHitLine: Int,
    val snippet: String,
)

class WikiSearcher(private val wikiPath: WikiPath) {
    fun search(query: String, pathTokens: List<String> = emptyList()): List<WikiSearchHit> {
        if (query.isBlank() && pathTokens.isEmpty()) return emptyList()
        val root = wikiPath.rootDir
        if (!Files.isDirectory(root)) return emptyList()
        val needle = query.lowercase().takeIf { it.isNotBlank() }
        val tokens = pathTokens
            .flatMap { it.lowercase().split("/") }
            .filter { it.isNotBlank() }
            .distinct()
        val hitsByPath = mutableMapOf<String, WikiSearchHit>()
        Files.walk(root).use { stream ->
            for (path in stream) {
                if (!Files.isRegularFile(path)) continue
                if (!path.fileName.toString().endsWith(".md")) continue
                val text = try { Files.readString(path) } catch (_: Exception) { continue }
                val lines = text.lines()
                val relativePath = root.relativize(path).toString().replace('\\', '/')
                val fileName = path.fileName.toString().lowercase()
                var firstHitLine = -1
                var firstSnippet = ""
                var matchCount = 0

                if (needle != null) {
                    for ((i, line) in lines.withIndex()) {
                        val occurrences = countSubstring(line.lowercase(), needle)
                        if (occurrences > 0) {
                            matchCount += occurrences
                            if (firstHitLine < 0) {
                                firstHitLine = i + 1
                                firstSnippet = line.trim()
                            }
                        }
                    }
                }

                if (tokens.isNotEmpty()) {
                    var tokenHits = 0
                    for (token in tokens) {
                        if (fileName.contains(token)) {
                            tokenHits++
                            continue
                        }
                        for ((i, line) in lines.withIndex()) {
                            val lower = line.lowercase()
                            if ((lower.startsWith("#") || lower.startsWith("##")) && lower.contains(token)) {
                                tokenHits++
                                if (firstHitLine < 0) {
                                    firstHitLine = i + 1
                                    firstSnippet = line.trim()
                                }
                                break
                            }
                        }
                    }
                    matchCount += tokenHits
                }

                if (matchCount > 0) {
                    hitsByPath[relativePath] = WikiSearchHit(
                        relativePath = relativePath,
                        matchCount = matchCount,
                        firstHitLine = if (firstHitLine > 0) firstHitLine else 1,
                        snippet = firstSnippet.ifEmpty { lines.firstOrNull()?.trim().orEmpty() },
                    )
                }
            }
        }
        return hitsByPath.values.sortedByDescending { it.matchCount }
    }

    private fun countSubstring(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            val found = haystack.indexOf(needle, startIndex = index)
            if (found < 0) break
            count++
            index = found + needle.length
        }
        return count
    }
}
