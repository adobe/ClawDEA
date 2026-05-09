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

data class WikiConceptLink(
    val targetSlug: String,
    val original: String,
)

object WikiLink {
    private val OLD_WIKILINK = Regex("""\[\[([^\]]+)]]""")
    private val MARKDOWN_LINK = Regex("""\[([^]]+)]\(([^)]+\.md)\)""")

    @Suppress("UNUSED_PARAMETER")
    fun extractConceptLinks(pageRelativePath: String, text: String): List<WikiConceptLink> {
        val oldLinks = OLD_WIKILINK.findAll(text).mapNotNull { match ->
            val slug = match.groupValues[1].trim()
            if (isValidOldWikiSlug(slug)) {
                LinkMatch(match.range.first, WikiConceptLink(targetSlug = slug, original = match.value))
            } else {
                null
            }
        }
        val markdownLinks = MARKDOWN_LINK.findAll(text).mapNotNull { match ->
            val slug = markdownSlug(match.groupValues[2])
            if (slug.isNotBlank()) {
                LinkMatch(match.range.first, WikiConceptLink(targetSlug = slug, original = match.value))
            } else {
                null
            }
        }

        return (oldLinks + markdownLinks).sortedBy { it.offset }.map { it.link }.toList()
    }

    fun toMarkdownLink(fromPage: String, targetSlug: String): String {
        val href = when {
            fromPage == "index.md" -> "concepts/$targetSlug.md"
            fromPage.startsWith("concepts/") -> "$targetSlug.md"
            else -> "../concepts/$targetSlug.md"
        }
        return "[${labelFor(targetSlug)}]($href)"
    }

    private fun isValidOldWikiSlug(slug: String): Boolean =
        slug.isNotBlank() &&
            ".." !in slug &&
            "/" !in slug &&
            "\\" !in slug

    private fun markdownSlug(href: String): String =
        href.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".md").trim()

    private fun labelFor(slug: String): String =
        slug.split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { segment ->
                segment.lowercase().replaceFirstChar { it.titlecase() }
            }

    private data class LinkMatch(
        val offset: Int,
        val link: WikiConceptLink,
    )
}
