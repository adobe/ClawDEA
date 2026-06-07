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
package com.adobe.clawdea.knowledge.primer.sources

import com.adobe.clawdea.CLAWDEA_DIR
import com.adobe.clawdea.knowledge.primer.PrimerSource
import com.intellij.openapi.project.Project
import java.io.File

class RepoStateSource : PrimerSource {

    override val id = "repoState"

    override fun load(project: Project): String? {
        val basePath = project.basePath ?: return null
        val file = File(basePath, "$CLAWDEA_DIR/REPO_STATE.md")
        if (!file.exists() || !file.canRead()) return null
        val text = file.readText().trim()
        return text.takeIf { it.isNotEmpty() }
    }
}
