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
package com.adobe.clawdea.actions.intentions

import com.adobe.clawdea.actions.ActionType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

class RefactorWithInstructionsAction : BaseClawDEAIntention() {
    override val actionType = ActionType.REFACTOR
    override val actionName = "Refactor with instructions... (ClawDEA)"
    override val needsUserInstructions = true

    override fun getUserInstructions(project: Project): String? {
        return Messages.showInputDialog(
            project,
            "Describe how you want this code refactored:",
            "ClawDEA: Refactor",
            null,
        )
    }
}
