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

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Per-project bridge between the single application-level [OAuthUsageService] poll and this
 * project's [CostTracker]. On [connect] it subscribes the project's tracker to the shared
 * [OAuthUsageCache] — which replays the latest usage immediately, so a project opening mid-cycle
 * shows usage at once — and starts the (idempotent) shared poll. The subscription is torn down when
 * the project (this service) disposes, so a closed project's tracker stops receiving updates.
 */
@Service(Service.Level.PROJECT)
class OAuthUsageProjectLink(private val project: Project) : Disposable {
    @Volatile private var unsubscribe: (() -> Unit)? = null

    /** Idempotent: subscribes this project's CostTracker to the shared usage cache once. */
    @Synchronized
    fun connect() {
        if (unsubscribe != null) return
        val service = OAuthUsageService.getInstance()
        unsubscribe = service.cache.subscribe { usage ->
            if (!project.isDisposed) CostTracker.getInstance(project).updateUsage(usage)
        }
        service.start()
    }

    @Synchronized
    override fun dispose() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    companion object {
        fun getInstance(project: Project): OAuthUsageProjectLink =
            project.getService(OAuthUsageProjectLink::class.java)
    }
}
