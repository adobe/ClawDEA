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
package com.adobe.clawdea.util

/** Filesystem-path helpers shared by the agent-edit and index paths (Tier 4.5). */
object ProjectPaths {

    /**
     * Whether [path] is absolute on the host OS. Several call sites tested `path.startsWith("/")`,
     * which is false for a Windows path like `C:\proj\Foo.kt` — the agent-edit write paths then
     * joined it onto the project base and the path-boundary check evaluated a fabricated path.
     * [java.io.File.isAbsolute] is host-aware (drive letters on Windows, leading slash on POSIX).
     */
    fun isAbsolutePath(path: String): Boolean = java.io.File(path).isAbsolute
}
