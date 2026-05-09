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

class WikiPageReader(private val wikiPath: WikiPath) {
    fun readConcept(name: String): String? = read(wikiPath.concept(name))
    fun readSource(name: String): String? = read(wikiPath.source(name))
    fun readIndex(): String? = read(wikiPath.index())

    private fun read(path: Path?): String? {
        if (path == null) return null
        if (!Files.exists(path) || !Files.isReadable(path)) return null
        return Files.readString(path).trim().takeIf { it.isNotEmpty() }
    }
}
