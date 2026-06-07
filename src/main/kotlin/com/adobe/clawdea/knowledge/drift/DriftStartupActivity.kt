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

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Runs `DriftDetectionService.rescan()` once per project open. The detectors
 * read the resolved wiki directory (via `WikiLocator`) and the workspace
 * manifest directly from disk; no ordering dependency on `PrimerService` or
 * other startup activities.
 */
class DriftStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<DriftDetectionService>().rescan()
    }
}
