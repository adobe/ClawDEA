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
package com.adobe.clawdea.buildtool

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A fully-specified command for invoking a build tool to compile a target.
 *
 * Produced by [BuildTool.compileCommandFor]; consumed by `McpIdeTools` to spawn
 * a subprocess via [ProcessBuilder].
 */
data class CompileCommand(
    val argv: List<String>,
    val workingDir: File,
    val timeout: Duration = 30.seconds,
)
