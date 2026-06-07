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
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Subscribes to Git4Idea's [GitRepository.GIT_REPO_CHANGE] message-bus topic
 * and triggers a debounced [DriftDetectionService.rescan]. Catches commits,
 * fetches, pulls, branch switches, and rebases — every case where HEAD or
 * a tracking ref changes — regardless of whether the user authored the change.
 *
 * Debounce is a cancellable coroutine on the service's intrinsic scope: each
 * git event cancels the pending rescan and schedules a fresh one after
 * [DEBOUNCE_MS]. `rescan` suspends rather than blocks, so the wiki-author
 * subprocess never pins a pooled thread.
 */
@Service(Service.Level.PROJECT)
class GitRepositoryChangeSubscriber(private val project: Project, private val scope: CoroutineScope) {

    private val pending = AtomicReference<Job?>(null)

    init {
        val connection = project.messageBus.connect()
        connection.subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { _ ->
                if (!ClawDEASettings.getInstance().state.enableWikiLibrarian) return@GitRepositoryChangeListener
                val job = scope.launch(Dispatchers.IO) {
                    // Debounce window is cancellable so a newer git event coalesces
                    // pending rescans. Once the rescan itself starts it runs under
                    // NonCancellable: cancelling it midway would kill an in-flight
                    // wiki-author subprocess and abandon the suggestion it was
                    // authoring (matches the old Alarm, which never interrupted a
                    // request already executing).
                    delay(DEBOUNCE_MS)
                    withContext(NonCancellable) {
                        try {
                            project.getService(DriftDetectionService::class.java).rescan()
                        } catch (e: Throwable) {
                            LOG.warn("Drift rescan after git change failed: ${e.message}")
                        }
                    }
                }
                pending.getAndSet(job)?.cancel()
            },
        )
    }

    companion object {
        private val LOG = Logger.getInstance(GitRepositoryChangeSubscriber::class.java)
        const val DEBOUNCE_MS = 5_000L
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
