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
package com.adobe.clawdea.knowledge.workspace.seed

import com.adobe.clawdea.language.LanguageSupportRegistry
import java.nio.file.Files
import java.nio.file.Path

object CandidateFingerprinter {

    private val JIRA_RX = Regex("\\b([A-Z][A-Z0-9]{1,9})-\\d+")
    private val POM_ARTIFACT_RX = Regex("<artifactId>([^<]+)</artifactId>")
    private val POM_DEP_RX = Regex(
        "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val PKG_DECL_RX = Regex("^package\\s+([\\w.]+)")
    private val IMPORT_RX = Regex("""^import\s+(?:static\s+)?([\w.]+)""")
    private val REMOTE_GH_SSH = Regex("git@([^:]+):([^/]+)/")
    private val REMOTE_HTTPS = Regex("https?://([^/]+)/([^/]+)/")

    fun fingerprint(path: Path, gitLog: List<String>): CandidateFingerprint {
        val pomFp = extractPomFingerprint(path)
        val srcFp = extractSourceFingerprint(path)
        return CandidateFingerprint(
            path = path,
            key = deriveKey(path),
            packageRoots = srcFp.packageRoots,
            pomArtifactId = extractPomArtifactId(path),
            pomArtifactIds = pomFp.artifactIds,
            pomGroupIds = pomFp.groupIds,
            pomDeps = extractPomDeps(path),
            javaImports = srcFp.javaImports,
            jiraPrefixes = histogramJiraPrefixes(gitLog),
            gitRemoteOrg = extractGitRemoteOrg(path),
        )
    }

    internal fun deriveKey(path: Path): String =
        path.fileName.toString().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    internal data class SourceFingerprint(val packageRoots: Set<String>, val javaImports: Set<String>)

    internal fun extractSourceFingerprint(root: Path): SourceFingerprint {
        val packageRoots = mutableSetOf<String>()
        val javaImports = mutableSetOf<String>()
        for (sub in listOf("src/main/java", "src/main/kotlin")) {
            val base = root.resolve(sub)
            if (!Files.isDirectory(base)) continue
            try {
                Files.walk(base, 6).use { stream ->
                    stream.filter { Files.isRegularFile(it) }
                        .filter { isSourceFile(it) }
                        .forEach { f ->
                            scanSourceFile(f, packageRoots, javaImports)
                        }
                }
            } catch (_: Throwable) { /* skip bad subtree */ }
        }
        return SourceFingerprint(packageRoots, javaImports)
    }

    private fun isSourceFile(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        val ext = name.substringAfterLast('.', missingDelimiterValue = "")
        if (ext.isEmpty()) return false
        if (LanguageSupportRegistry.forFileExtension(ext) != null) return true
        // Fallback for early-indexing scenarios where the registry hasn't been populated yet.
        return ext == "java" || ext == "kt" || ext == "kts"
    }

    private fun scanSourceFile(file: Path, pkgOut: MutableSet<String>, importOut: MutableSet<String>) {
        try {
            Files.lines(file).use { stream ->
                for (raw in stream.iterator()) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    if (line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) continue
                    if (line.startsWith("package")) {
                        PKG_DECL_RX.find(line)?.let { m ->
                            val parts = m.groupValues[1].split('.')
                            if (parts.size >= 2) pkgOut.add("${parts[0]}.${parts[1]}")
                        }
                        continue
                    }
                    val m = IMPORT_RX.find(line)
                    if (m == null) {
                        // Past the import block once we see something that isn't blank/comment/package/import.
                        if (!line.startsWith("import")) return
                        continue
                    }
                    val full = m.groupValues[1]
                    val firstSegment = full.substringBefore('.')
                    if (firstSegment == "java" || firstSegment == "kotlin") continue
                    val parts = full.split('.')
                    if (parts.size >= 2) importOut.add("${parts[0]}.${parts[1]}")
                }
            }
        } catch (_: Throwable) { /* skip bad file */ }
    }

    internal fun extractPomArtifactId(root: Path): String? {
        val pom = root.resolve("pom.xml")
        if (!Files.isRegularFile(pom)) return null
        val text = runCatching { Files.readString(pom) }.getOrNull() ?: return null
        return POM_ARTIFACT_RX.find(text)?.groupValues?.get(1)
    }

    internal data class PomFingerprint(val artifactIds: Set<String>, val groupIds: Set<String>)

    internal fun extractPomFingerprint(root: Path): PomFingerprint {
        val artifactIds = mutableSetOf<String>()
        val groupIds = mutableSetOf<String>()
        if (!Files.isDirectory(root)) return PomFingerprint(emptySet(), emptySet())
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            // Defensive: avoid XXE on untrusted poms
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isNamespaceAware = false
        }
        try {
            Files.walk(root, 6).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName?.toString() == "pom.xml" }
                    .forEach { pom -> extractProjectCoords(pom, factory, artifactIds, groupIds) }
            }
        } catch (_: Throwable) { /* skip bad subtree */ }
        return PomFingerprint(artifactIds, groupIds)
    }

    private fun extractProjectCoords(
        pom: Path,
        factory: javax.xml.parsers.DocumentBuilderFactory,
        artifactIds: MutableSet<String>,
        groupIds: MutableSet<String>,
    ) {
        try {
            val builder = factory.newDocumentBuilder()
            // Suppress error reporting to stderr for malformed poms
            builder.setErrorHandler(object : org.xml.sax.ErrorHandler {
                override fun warning(e: org.xml.sax.SAXParseException) {}
                override fun error(e: org.xml.sax.SAXParseException) {}
                override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            })
            val doc = Files.newInputStream(pom).use { builder.parse(it) }
            val project = doc.documentElement ?: return
            if (project.nodeName != "project") return
            // Direct children of <project>: capture <groupId> and <artifactId>; recurse into <parent> only.
            val children = project.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                when (child.nodeName) {
                    "artifactId" -> child.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { artifactIds.add(it) }
                    "groupId" -> child.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { groupIds.add(it) }
                    "parent" -> {
                        val parentChildren = child.childNodes
                        for (j in 0 until parentChildren.length) {
                            val pc = parentChildren.item(j)
                            if (pc.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                            when (pc.nodeName) {
                                "artifactId" -> pc.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { artifactIds.add(it) }
                                "groupId" -> pc.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { groupIds.add(it) }
                            }
                        }
                    }
                    // <dependencies>, <dependencyManagement>, <build>, <profiles>, <reporting>, etc. — skip entirely
                }
            }
        } catch (_: Throwable) { /* skip bad pom */ }
    }

    internal fun extractPomDeps(root: Path): Set<String> {
        val pom = root.resolve("pom.xml")
        if (!Files.isRegularFile(pom)) return emptySet()
        val text = runCatching { Files.readString(pom) }.getOrNull() ?: return emptySet()
        return POM_DEP_RX.findAll(text)
            .map { "${it.groupValues[1]}:${it.groupValues[2]}" }
            .toSet()
    }

    internal fun histogramJiraPrefixes(gitLog: List<String>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (line in gitLog) {
            JIRA_RX.findAll(line).forEach { m ->
                counts.merge(m.groupValues[1], 1, Int::plus)
            }
        }
        return counts
    }

    internal fun extractGitRemoteOrg(root: Path): String? {
        val cfg = root.resolve(".git/config")
        if (!Files.isRegularFile(cfg)) return null
        val text = runCatching { Files.readString(cfg) }.getOrNull() ?: return null
        val urlLine = text.lines().firstOrNull { it.trim().startsWith("url =") } ?: return null
        val url = urlLine.substringAfter("=").trim()
        return parseRemoteOrg(url)
    }

    fun parseRemoteOrg(url: String): String? {
        REMOTE_GH_SSH.find(url)?.let { return "${it.groupValues[1]}/${it.groupValues[2]}" }
        REMOTE_HTTPS.find(url)?.let { return "${it.groupValues[1]}/${it.groupValues[2]}" }
        return null
    }
}
