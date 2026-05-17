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
package com.adobe.clawdea.chat

import com.adobe.clawdea.knowledge.drift.DriftDetectionService
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.util.Alarm
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * Subscribes to Git4Idea's [GitRepository.GIT_REPO_CHANGE] message-bus topic
 * and triggers a debounced [DriftDetectionService.rescan]. Catches commits,
 * fetches, pulls, branch switches, and rebases — every case where HEAD or
 * a tracking ref changes — regardless of whether the user authored the change.
 */
@Service(Service.Level.PROJECT)
class GitRepositoryChangeSubscriber(private val project: Project) {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    init {
        val connection = project.messageBus.connect()
        connection.subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { _ ->
                if (!ClawDEASettings.getInstance().state.enableWikiLibrarian) return@GitRepositoryChangeListener
                alarm.cancelAllRequests()
                alarm.addRequest({
                    try {
                        project.getService(DriftDetectionService::class.java).rescan()
                    } catch (e: Throwable) {
                        LOG.warn("Drift rescan after git change failed: ${e.message}")
                    }
                }, DEBOUNCE_MS)
            },
        )
    }

    companion object {
        private val LOG = Logger.getInstance(GitRepositoryChangeSubscriber::class.java)
        const val DEBOUNCE_MS = 5_000
    }
}

/**
 * Forces lazy-init of [GitRepositoryChangeSubscriber] at project open so its
 * message-bus subscription is registered before any git event arrives.
 */
class GitRepositoryChangeSubscriberStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        project.getService(GitRepositoryChangeSubscriber::class.java)
    }
}
