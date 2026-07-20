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
package com.adobe.clawdea.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogMergerTest {

    @Test
    fun `merge keeps all fetched entries when no user entries exist`() {
        val existing = listOf(
            ModelEntry(id = "a", displayName = "A", userAdded = false),
        )
        val fetched = listOf(
            ModelEntry(id = "x", displayName = "X"),
            ModelEntry(id = "y", displayName = "Y"),
        )
        assertEquals(fetched, ModelCatalogMerger.merge(existing, fetched))
    }

    @Test
    fun `merge preserves userAdded entries at the top`() {
        val existing = listOf(
            ModelEntry(id = "u1", displayName = "User 1", userAdded = true),
            ModelEntry(id = "a",  displayName = "A",       userAdded = false),
        )
        val fetched = listOf(
            ModelEntry(id = "x", displayName = "X"),
            ModelEntry(id = "y", displayName = "Y"),
        )
        val result = ModelCatalogMerger.merge(existing, fetched)
        assertEquals(listOf("u1", "x", "y"), result.map { it.id })
    }

    @Test
    fun `merge drops a fetched entry whose id collides with a userAdded entry`() {
        val existing = listOf(
            ModelEntry(id = "x", displayName = "User X", userAdded = true),
        )
        val fetched = listOf(
            ModelEntry(id = "x", displayName = "Probe X"),
            ModelEntry(id = "y", displayName = "Y"),
        )
        val result = ModelCatalogMerger.merge(existing, fetched)
        assertEquals(2, result.size)
        assertEquals("User X", result[0].displayName) // user's displayName wins
        assertEquals("y", result[1].id)
    }

    @Test
    fun `merge preserves userAdded entries when fetched is empty`() {
        val existing = listOf(
            ModelEntry(id = "u1", displayName = "User 1", userAdded = true),
        )
        val result = ModelCatalogMerger.merge(existing, emptyList())
        assertEquals(existing, result)
    }

    @Test
    fun `merge of empty user and empty fetched returns empty`() {
        assertEquals(emptyList<ModelEntry>(), ModelCatalogMerger.merge(emptyList(), emptyList()))
    }

    @Test
    fun `merge carries over user-edited fields from existing non-userAdded entries`() {
        val existing = listOf(
            ModelEntry(
                id = "model-a",
                displayName = "Old Name",
                userAdded = false,
                enabled = false,
                capability = "agentic",
                inputPerM = 1.5,
                outputPerM = 3.0,
                cachedInputPerM = 0.5,
                reasoningPerM = 2.0,
            ),
        )
        val fetched = listOf(
            ModelEntry(
                id = "model-a",
                displayName = "New Name",
                userAdded = false,
                enabled = true,
                capability = "unknown",
                inputPerM = 0.0,
                outputPerM = 0.0,
                cachedInputPerM = 0.0,
                reasoningPerM = 0.0,
            ),
        )
        val result = ModelCatalogMerger.merge(existing, fetched)
        assertEquals(1, result.size)
        assertEquals("model-a", result[0].id)
        assertEquals("New Name", result[0].displayName) // fresh displayName
        assertEquals(false, result[0].enabled) // carried over
        assertEquals("agentic", result[0].capability) // carried over
        assertEquals(1.5, result[0].inputPerM, 0.001) // carried over
        assertEquals(3.0, result[0].outputPerM, 0.001) // carried over
        assertEquals(0.5, result[0].cachedInputPerM, 0.001) // carried over
        assertEquals(2.0, result[0].reasoningPerM, 0.001) // carried over
    }

    @Test
    fun `merge does not carry over from userAdded entries to fetched entries`() {
        val existing = listOf(
            ModelEntry(
                id = "model-a",
                displayName = "User Model",
                userAdded = true,
                enabled = false,
            ),
        )
        val fetched = listOf(
            ModelEntry(
                id = "model-b",
                displayName = "Fetched Model",
                userAdded = false,
                enabled = true,
            ),
        )
        val result = ModelCatalogMerger.merge(existing, fetched)
        assertEquals(2, result.size)
        assertEquals("model-a", result[0].id)
        assertEquals(false, result[0].enabled)
        assertEquals("model-b", result[1].id)
        assertEquals(true, result[1].enabled) // not carried over
    }
}
