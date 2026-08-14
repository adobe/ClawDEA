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

/**
 * Interrupts a running child process in a cross-platform way.
 *
 * The Claude CLI is paused/aborted by delivering SIGINT. `kill` does not exist on Windows, so the
 * previous `Runtime.exec(["kill", ...])` threw `IOException` there, was swallowed, and the turn was
 * never actually interrupted — it kept generating and billing while Stop/ESC appeared to do nothing
 * (Tier 4.2b). On Windows there are no POSIX signals, so a graceful `destroy()` is the closest
 * available stop.
 */
object ProcessInterrupter {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    /** Returns true when the interrupt was delivered; false if it could not be. */
    fun interrupt(process: Process): Boolean {
        return try {
            if (isWindows) {
                process.destroy()
                true
            } else {
                Runtime.getRuntime().exec(arrayOf("kill", "-INT", process.pid().toString())).waitFor() == 0
            }
        } catch (_: Exception) {
            false
        }
    }
}
