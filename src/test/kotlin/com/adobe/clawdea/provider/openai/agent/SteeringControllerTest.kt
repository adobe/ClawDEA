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
package com.adobe.clawdea.provider.openai.agent

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringControllerTest {

    @Test
    fun `steer returns false when no turn is active`() = runTest {
        val controller = SteeringController()
        assertFalse(controller.steer("hello"))
    }

    @Test
    fun `steer cancels the active job and records pending steer`() = runTest {
        val controller = SteeringController()
        val job = Job()
        controller.setActiveJob(job)

        assertTrue(controller.steer("change direction"))
        assertTrue(job.isCancelled)
        assertEquals("change direction", controller.consumePendingSteer())
    }

    @Test
    fun `consumePendingSteer returns null when nothing pending`() = runTest {
        val controller = SteeringController()
        assertNull(controller.consumePendingSteer())
    }

    @Test
    fun `consumePendingSteer clears after read`() = runTest {
        val controller = SteeringController()
        val job = Job()
        controller.setActiveJob(job)
        controller.steer("steer text")

        assertEquals("steer text", controller.consumePendingSteer())
        assertNull(controller.consumePendingSteer())
    }

    @Test
    fun `clearActiveJob makes subsequent steer return false`() = runTest {
        val controller = SteeringController()
        val job = Job()
        controller.setActiveJob(job)
        controller.clearActiveJob()

        assertFalse(controller.steer("no active job"))
    }

    @Test
    fun `consumePendingSteerOrClear returns pending steer without clearing active job`() = runTest {
        val controller = SteeringController()
        val job = Job()
        controller.setActiveJob(job)
        controller.steer("keep going")

        // Steer branch: returns the text; a follow-up steer must still be accepted (job not cleared).
        assertEquals("keep going", controller.consumePendingSteerOrClear(job))
        assertTrue(controller.steer("again"))
    }

    @Test
    fun `consumePendingSteerOrClear clears active job when nothing pending`() = runTest {
        val controller = SteeringController()
        val job = Job()
        controller.setActiveJob(job)

        // No-steer branch: returns null and clears the job so a concurrent steer sees no active turn.
        assertNull(controller.consumePendingSteerOrClear(job))
        assertFalse(controller.steer("too late"))
    }

    @Test
    fun `consumePendingSteerOrClear does not clobber a newer turn's job`() = runTest {
        val controller = SteeringController()
        val oldJob = Job()
        val newJob = Job()
        controller.setActiveJob(oldJob)
        controller.setActiveJob(newJob)

        // Completing the OLD round must not clear the NEW turn's active job (compare-and-clear).
        assertNull(controller.consumePendingSteerOrClear(oldJob))
        assertTrue(controller.steer("steer the new turn"))
        assertTrue(newJob.isCancelled)
    }

    @Test
    fun `clearActiveJob with job identity does not clobber a newer turn`() = runTest {
        val controller = SteeringController()
        val oldJob = Job()
        val newJob = Job()
        controller.setActiveJob(oldJob)
        controller.setActiveJob(newJob)

        controller.clearActiveJob(oldJob)
        // newJob is still active.
        assertTrue(controller.steer("still steerable"))
    }
}
