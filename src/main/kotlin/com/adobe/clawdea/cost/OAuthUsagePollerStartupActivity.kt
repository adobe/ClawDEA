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
package com.adobe.clawdea.cost

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * On project open, subscribes this project's CostTracker to the single application-level
 * oauth/usage poll (and starts that poll, idempotently). See [OAuthUsageProjectLink].
 */
class OAuthUsagePollerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        OAuthUsageProjectLink.getInstance(project).connect()
    }
}
