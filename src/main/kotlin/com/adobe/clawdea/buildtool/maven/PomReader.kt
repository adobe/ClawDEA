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
package com.adobe.clawdea.buildtool.maven

import com.intellij.openapi.diagnostic.Logger
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Read-only DOM-based `pom.xml` introspection. The parser is XXE-safe (no external
 * entities, no DTDs, no XInclude). All methods are forgiving — parse failures,
 * missing files, and malformed shapes return empty/false rather than throwing.
 */
object PomReader {
    private val log = Logger.getInstance(PomReader::class.java)
    private const val DEFAULT_MAX_DEPTH = 8

    /** Returns text content of `<project><modules><module>` elements in [pomFile]. */
    fun readModules(pomFile: File): List<String> {
        val doc = parsePom(pomFile) ?: return emptyList()
        val root = doc.documentElement ?: return emptyList()
        val modulesEl = firstChildElement(root, "modules") ?: return emptyList()
        return childElements(modulesEl, "module").mapNotNull { it.textContent?.trim()?.takeIf { t -> t.isNotEmpty() } }
    }

    /** Returns the trimmed text of the project-level `<description>` element, or null. */
    fun readDescription(pomFile: File): String? {
        val doc = parsePom(pomFile) ?: return null
        val root = doc.documentElement ?: return null
        return firstChildElement(root, "description")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** `groupId` and `artifactId` from `<project>` and its `<parent>` element, deduplicated. */
    data class Coords(val groupIds: Set<String>, val artifactIds: Set<String>)

    fun readCoords(pomFile: File): Coords {
        val doc = parsePom(pomFile) ?: return Coords(emptySet(), emptySet())
        val root = doc.documentElement ?: return Coords(emptySet(), emptySet())
        val groupIds = mutableSetOf<String>()
        val artifactIds = mutableSetOf<String>()
        firstChildElement(root, "groupId")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { groupIds += it }
        firstChildElement(root, "artifactId")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { artifactIds += it }
        firstChildElement(root, "parent")?.let { parent ->
            firstChildElement(parent, "groupId")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { groupIds += it }
            firstChildElement(parent, "artifactId")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { artifactIds += it }
        }
        return Coords(groupIds, artifactIds)
    }

    /**
     * True if [pomFile] declares a plugin matching [artifactId] under either
     * `<build><plugins>` or `<build><pluginManagement><plugins>`. A `<groupId>` match
     * is required when present in the plugin element; absent groupId is treated as
     * a match (Maven's default-groupId fallback behavior).
     */
    fun hasPlugin(pomFile: File, groupId: String, artifactId: String): Boolean {
        val doc = parsePom(pomFile) ?: return false
        val root = doc.documentElement ?: return false
        val buildEl = firstChildElement(root, "build") ?: return false
        return matchesPlugin(firstChildElement(buildEl, "plugins"), groupId, artifactId) ||
            matchesPlugin(
                firstChildElement(buildEl, "pluginManagement")?.let { firstChildElement(it, "plugins") },
                groupId,
                artifactId,
            )
    }

    /**
     * Walks the module tree rooted at [rootPomFile] depth-first. Each `<module>` is
     * resolved as `<parent-dir>/<module-name>/pom.xml`. Cycles are guarded via a
     * visited-set keyed on canonical paths. Missing module poms are silently skipped.
     * Returns every pom found, including [rootPomFile] itself.
     */
    fun walkPomTree(rootPomFile: File, maxDepth: Int = DEFAULT_MAX_DEPTH): List<File> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<File>()
        walk(rootPomFile, visited, result, depth = 0, maxDepth = maxDepth)
        return result
    }

    private fun walk(pom: File, visited: MutableSet<String>, out: MutableList<File>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        if (!pom.exists() || !pom.isFile) return
        val canonical = try {
            pom.canonicalPath
        } catch (_: Throwable) {
            pom.absolutePath
        }
        if (!visited.add(canonical)) return
        out.add(pom)
        val parentDir = pom.parentFile ?: return
        for (moduleName in readModules(pom)) {
            val childPom = File(parentDir, "$moduleName/pom.xml")
            walk(childPom, visited, out, depth + 1, maxDepth)
        }
    }

    private fun matchesPlugin(pluginsEl: Element?, groupId: String, artifactId: String): Boolean {
        if (pluginsEl == null) return false
        for (plugin in childElements(pluginsEl, "plugin")) {
            val artifactEl = firstChildElement(plugin, "artifactId") ?: continue
            if (artifactEl.textContent?.trim() != artifactId) continue
            val groupEl = firstChildElement(plugin, "groupId")
            if (groupEl == null) return true   // Maven's default-groupId fallback.
            if (groupEl.textContent?.trim() == groupId) return true
        }
        return false
    }

    // Factory configuration is invariant; lazily initialised once and shared.
    // DocumentBuilder instances are not thread-safe, so we still create one per parse.
    private val factory: DocumentBuilderFactory by lazy {
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
    }

    private fun parsePom(file: File): Document? {
        if (!file.exists() || !file.isFile) return null
        return try {
            factory.newDocumentBuilder().parse(file)
        } catch (t: Throwable) {
            log.debug("PomReader: failed to parse ${file.path}: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun firstChildElement(parent: Element, tagName: String): Element? {
        val children: NodeList = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tagName) {
                return node
            }
        }
        return null
    }

    private fun childElements(parent: Element, tagName: String): List<Element> {
        val children: NodeList = parent.childNodes
        val result = mutableListOf<Element>()
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tagName) {
                result.add(node)
            }
        }
        return result
    }
}
