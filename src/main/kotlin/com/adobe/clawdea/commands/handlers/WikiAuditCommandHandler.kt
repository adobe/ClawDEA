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
package com.adobe.clawdea.commands.handlers

import com.adobe.clawdea.commands.CommandCategory
import com.adobe.clawdea.commands.CommandContext
import com.adobe.clawdea.commands.CommandHandler
import com.adobe.clawdea.commands.CommandInfo
import com.adobe.clawdea.knowledge.wiki.WikiAuditor
import com.adobe.clawdea.knowledge.wiki.WikiPath
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.project.Project
import java.nio.file.Paths

class WikiAuditCommandHandler(private val project: Project) : CommandHandler {
    override val info = CommandInfo(
        "/wiki-audit",
        "Lint the project wiki for orphans and broken links",
        CommandCategory.LOCAL,
    )

    override fun execute(args: String, context: CommandContext) {
        val basePath = project.basePath
        if (basePath == null) {
            context.appendHtml("""<div class="info-block">No project basePath; cannot audit wiki.</div>""")
            return
        }
        val state = ClawDEASettings.getInstance().state
        val wikiPath = WikiPath(Paths.get(basePath, state.claudeDirName, state.wikiSubdir))
        val report = WikiAuditor(wikiPath).audit()
        val text = report.format()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
        context.appendHtml("""<div class="info-block"><pre>$text</pre></div>""")
    }
}
