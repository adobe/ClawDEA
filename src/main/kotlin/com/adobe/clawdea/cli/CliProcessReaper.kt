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

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor

/**
 * Safety-net reaper for orphaned `claude` subprocesses. Drains
 * [CliProcessRegistry] when the plugin is unloaded (dynamic reinstall) or the
 * IDE is closing — the two cases the per-tab [com.intellij.openapi.Disposable]
 * chain does not reliably cover. See [CliProcessRegistry] for why this is
 * needed. Registered as an application listener in plugin.xml.
 */
class CliProcessReaper : DynamicPluginListener, AppLifecycleListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId?.idString == "com.adobe.clawdea") {
            CliProcessRegistry.killAll()
        }
    }

    override fun appWillBeClosed(isRestart: Boolean) {
        CliProcessRegistry.killAll()
    }
}
