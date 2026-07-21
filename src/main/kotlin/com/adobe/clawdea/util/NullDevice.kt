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

import java.io.File

/**
 * The platform null device, used to hand a one-shot subprocess immediate EOF on stdin so it does
 * not block waiting for input (e.g. `claude -p`, `codex exec`, which read stdin as an extra input
 * block and hang on an open pipe until EOF).
 *
 * On Windows the null device is `NUL`, not `/dev/null` — passing `/dev/null` to `ProcessBuilder`
 * there resolves to a non-existent relative path and `Redirect.from` throws when the process
 * starts, which is exactly the failure mode this centralizes away from call sites.
 */
object NullDevice {

    /** The null-device path for [osName] (`NUL` on Windows, `/dev/null` elsewhere). */
    fun path(osName: String = System.getProperty("os.name").orEmpty()): String =
        if (osName.lowercase().contains("windows")) "NUL" else "/dev/null"

    /** The null device as a [File], for `ProcessBuilder.Redirect.from(...)`. */
    fun file(osName: String = System.getProperty("os.name").orEmpty()): File = File(path(osName))

    /** A `ProcessBuilder` stdin redirect that yields immediate EOF, portable across OSes. */
    fun inputRedirect(osName: String = System.getProperty("os.name").orEmpty()): ProcessBuilder.Redirect =
        ProcessBuilder.Redirect.from(file(osName))
}
