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
import java.nio.file.Path

data class BrokenLink(val fromPage: String, val linkTarget: String)
data class WikiAuditReport(
    val orphans: List<String>,
    val brokenLinks: List<BrokenLink>,
) {
    fun format(): String {
        val sb = StringBuilder()
        sb.appendLine("Wiki audit report")
        sb.appendLine("=================")
        sb.appendLine()
        if (orphans.isEmpty()) sb.appendLine("Orphan pages: none")
        else {
            sb.appendLine("Orphan pages (${orphans.size}):")
            for (page in orphans) sb.appendLine("  - $page")
        }
        sb.appendLine()
        if (brokenLinks.isEmpty()) sb.appendLine("Broken links: none")
        else {
            sb.appendLine("Broken links (${brokenLinks.size}):")
            for (b in brokenLinks) sb.appendLine("  - ${b.fromPage} → [[${b.linkTarget}]]")
        }
        return sb.toString()
    }
}

class WikiAuditor(private val wikiPath: WikiPath) {
    fun audit(): WikiAuditReport {
        val root = wikiPath.rootDir
        if (!Files.isDirectory(root)) return WikiAuditReport(emptyList(), emptyList())

        val concepts = mutableMapOf<String, Path>()
        val pageLinks = mutableMapOf<String, Set<String>>()
        val indexLinks = mutableSetOf<String>()

        val indexPath = wikiPath.index()
        if (Files.exists(indexPath)) {
            indexLinks += extractLinks("index.md", Files.readString(indexPath))
        }

        val conceptsDir = root.resolve("concepts")
        if (Files.isDirectory(conceptsDir)) {
            Files.walk(conceptsDir).use { stream ->
                for (path in stream) {
                    if (!Files.isRegularFile(path)) continue
                    val name = path.fileName.toString()
                    if (!name.endsWith(".md")) continue
                    val key = name.removeSuffix(".md")
                    concepts[key] = path
                    pageLinks[key] = extractLinks("concepts/$name", Files.readString(path))
                }
            }
        }

        val orphans = mutableListOf<String>()
        for ((name, path) in concepts) {
            val linkedFromIndex = name in indexLinks
            val linkedFromOtherConcept = pageLinks.entries.any { (other, links) ->
                other != name && name in links
            }
            if (!linkedFromIndex && !linkedFromOtherConcept) {
                orphans.add(root.relativize(path).toString().replace('\\', '/'))
            }
        }

        val brokenLinks = mutableListOf<BrokenLink>()
        for ((name, links) in pageLinks) {
            for (target in links) {
                if (target !in concepts) {
                    val fromPath = "concepts/$name.md"
                    brokenLinks.add(BrokenLink(fromPage = fromPath, linkTarget = target))
                }
            }
        }

        return WikiAuditReport(orphans = orphans.sorted(), brokenLinks = brokenLinks)
    }

    private fun extractLinks(pageRelativePath: String, text: String): Set<String> =
        WikiLink.extractConceptLinks(pageRelativePath, text).map { it.targetSlug }.toSet()
}
