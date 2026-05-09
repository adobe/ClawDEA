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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.util.Alarm
import git4idea.repo.GitRepositoryManager

internal interface DebounceScheduler {
    fun schedule(delayMs: Long, task: () -> Unit)
    fun cancelAll()
}

internal interface RefreshOperations {
    fun refreshBroad()
    fun refreshFile(filePath: String)
}

@Service(Service.Level.PROJECT)
class FilesystemRefreshCoordinator internal constructor(
    private val ops: RefreshOperations,
    private val scheduler: DebounceScheduler,
) {

    @Suppress("unused")
    constructor(project: Project) : this(
        ops = PlatformRefreshOperations(project),
        scheduler = AlarmDebounceScheduler(project),
    )

    fun onEditApplied(filePath: String) {
        ops.refreshFile(filePath)
    }

    fun onBashCompleted() {
        scheduler.cancelAll()
        scheduler.schedule(BASH_DEBOUNCE_MS) { ops.refreshBroad() }
    }

    companion object {
        const val BASH_DEBOUNCE_MS: Long = 1000L
    }
}

internal class PlatformRefreshOperations(private val project: Project) : RefreshOperations {

    private val log = Logger.getInstance(PlatformRefreshOperations::class.java)

    override fun refreshBroad() {
        try {
            VirtualFileManager.getInstance().asyncRefresh(null)
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
                    GitRepositoryManager.getInstance(project).repositories.forEach { it.update() }
                } catch (t: Throwable) {
                    log.warn("VCS/git broad refresh failed", t)
                }
            }
        } catch (t: Throwable) {
            log.warn("broad refresh failed", t)
        }
    }

    override fun refreshFile(filePath: String) {
        try {
            val vFile = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(filePath)
                ?.also { it.refresh(false, false) }
            if (vFile == null) {
                log.debug("refreshFile: no VirtualFile for $filePath")
                return
            }

            val docManager = FileDocumentManager.getInstance()
            val doc = docManager.getCachedDocument(vFile)
            if (doc != null) {
                ApplicationManager.getApplication().invokeLater {
                    WriteAction.run<Exception> {
                        docManager.reloadFromDisk(doc)
                    }
                }
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    VcsDirtyScopeManager.getInstance(project).fileDirty(vFile)
                    GitRepositoryManager.getInstance(project).getRepositoryForFile(vFile)?.update()
                } catch (t: Throwable) {
                    log.warn("VCS/git refresh failed for $filePath", t)
                }
            }
        } catch (t: Throwable) {
            log.warn("file refresh failed for $filePath", t)
        }
    }
}

internal class AlarmDebounceScheduler(project: Project) : DebounceScheduler {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    override fun schedule(delayMs: Long, task: () -> Unit) {
        alarm.addRequest(task, delayMs.toInt())
    }

    override fun cancelAll() {
        alarm.cancelAllRequests()
    }
}
