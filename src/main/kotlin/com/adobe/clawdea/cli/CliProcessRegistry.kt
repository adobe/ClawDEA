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
package com.adobe.clawdea.cli

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-global registry of every long-lived `claude` subprocess ClawDEA has
 * spawned (the persistent per-tab chat CLIs). It exists purely as a safety net.
 *
 * The normal lifecycle is already correct: each [CliProcess] is owned by a
 * [CliBridge], which is a [com.intellij.openapi.Disposable] registered under the
 * chat tab's content (and transitively the project), so closing a tab or project
 * calls [CliProcess.stop] and the subprocess dies.
 *
 * What that path does NOT cover:
 *  - **Dynamic plugin unload** (every reinstall during development): IntelliJ may
 *    tear the plugin down without reliably disposing project-scoped UI, leaving
 *    the persistent CLIs orphaned and reparented to launchd/init.
 *  - **Ungraceful IDE exit**: on Unix a child process is not killed when its
 *    parent dies; it is reparented and keeps running.
 *
 * [CliProcessReaper] drains this registry on `beforePluginUnload` and on app
 * close, so neither case leaves stray `claude` processes behind.
 */
object CliProcessRegistry {
    private val log = Logger.getInstance(CliProcessRegistry::class.java)
    private val processes = ConcurrentHashMap.newKeySet<Process>()

    fun register(process: Process) {
        processes.add(process)
    }

    fun unregister(process: Process) {
        processes.remove(process)
    }

    /** Force-kills every still-alive registered process. Returns how many it killed. */
    fun killAll(): Int {
        var killed = 0
        val snapshot = processes.toList()
        processes.clear()
        for (p in snapshot) {
            if (p.isAlive) {
                try {
                    p.destroyForcibly()
                    killed++
                } catch (e: Throwable) {
                    log.warn("Failed to kill orphaned CLI process pid=${runCatching { p.pid() }.getOrNull()}: ${e.message}")
                }
            }
        }
        if (killed > 0) log.info("CliProcessRegistry reaped $killed orphaned CLI process(es)")
        return killed
    }
}
