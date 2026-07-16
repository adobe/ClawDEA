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

import com.intellij.openapi.progress.ProcessCanceledException

/**
 * Runs [block] against a single index-derived element, tolerating a stale
 * index/PSI state. Forcing AST access on a stub whose file changed on disk
 * before the VFS caught up throws platform exceptions such as
 * `StubTreeAndIndexUnmatchException` ("Outdated stub in index"); when that
 * happens we return `null` so the caller can skip that one entry and keep
 * returning results, rather than aborting the whole query.
 *
 * [ProcessCanceledException] is rethrown — it must never be swallowed, per the
 * IntelliJ platform contract.
 *
 * @param onSkip invoked with the caught exception when an entry is skipped.
 */
internal inline fun <T> ignoringStaleIndex(onSkip: (Exception) -> Unit = {}, block: () -> T): T? =
    try {
        block()
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        onSkip(e)
        null
    }
