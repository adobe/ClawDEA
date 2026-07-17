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
package com.adobe.clawdea.settings

import com.adobe.clawdea.gateway.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogMergeTest {

    @Test
    fun `empty existing and fresh results in empty merged`() {
        val merged = ModelCatalogMerge.merge(emptyList(), emptyList())
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `fresh models with empty existing returns fresh as-is`() {
        val fresh = listOf(
            ModelEntry(id = "model-1", displayName = "Model 1", inputPerM = 1.0),
            ModelEntry(id = "model-2", displayName = "Model 2", inputPerM = 2.0),
        )
        val merged = ModelCatalogMerge.merge(emptyList(), fresh)
        assertEquals(2, merged.size)
        assertEquals("model-1", merged[0].id)
        assertEquals("model-2", merged[1].id)
    }

    @Test
    fun `user-added rows are preserved even if not in fresh`() {
        val existing = listOf(
            ModelEntry(id = "user-model", displayName = "User Model", userAdded = true, inputPerM = 99.0),
        )
        val merged = ModelCatalogMerge.merge(existing, emptyList())
        assertEquals(1, merged.size)
        assertEquals("user-model", merged[0].id)
        assertTrue(merged[0].userAdded)
        assertEquals(99.0, merged[0].inputPerM, 0.0001)
    }

    @Test
    fun `non-user-added models removed from fresh are dropped`() {
        val existing = listOf(
            ModelEntry(id = "old-model", displayName = "Old Model", userAdded = false),
        )
        val merged = ModelCatalogMerge.merge(existing, emptyList())
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `user-modified pricing is preserved when model is in both catalogs`() {
        val existing = listOf(
            ModelEntry(
                id = "common-model",
                displayName = "Old Display",
                userAdded = false,
                inputPerM = 10.0,
                outputPerM = 20.0,
                enabled = true,
            ),
        )
        val fresh = listOf(
            ModelEntry(
                id = "common-model",
                displayName = "New Display",
                userAdded = false,
                inputPerM = 5.0,
                outputPerM = 15.0,
                enabled = true,
            ),
        )
        val merged = ModelCatalogMerge.merge(existing, fresh)
        assertEquals(1, merged.size)
        assertEquals("common-model", merged[0].id)
        assertEquals("New Display", merged[0].displayName) // fresh metadata
        assertEquals(10.0, merged[0].inputPerM, 0.0001) // user pricing preserved
        assertEquals(20.0, merged[0].outputPerM, 0.0001) // user pricing preserved
    }

    @Test
    fun `fresh model with same pricing as existing uses fresh entry`() {
        val existing = listOf(
            ModelEntry(
                id = "same-model",
                displayName = "Old Display",
                userAdded = false,
                inputPerM = 5.0,
                outputPerM = 10.0,
                enabled = false,
            ),
        )
        val fresh = listOf(
            ModelEntry(
                id = "same-model",
                displayName = "Fresh Display",
                userAdded = false,
                inputPerM = 5.0,
                outputPerM = 10.0,
                capability = "agentic",
                enabled = true,
            ),
        )
        val merged = ModelCatalogMerge.merge(existing, fresh)
        assertEquals(1, merged.size)
        assertEquals("same-model", merged[0].id)
        assertEquals("Fresh Display", merged[0].displayName)
        assertEquals("agentic", merged[0].capability)
        assertEquals(5.0, merged[0].inputPerM, 0.0001)
        assertEquals(10.0, merged[0].outputPerM, 0.0001)
        assertEquals(false, merged[0].enabled) // enabled is preserved from existing
    }

    @Test
    fun `new models from fresh are appended`() {
        val existing = listOf(
            ModelEntry(id = "existing-model", displayName = "Existing", userAdded = false),
        )
        val fresh = listOf(
            ModelEntry(id = "existing-model", displayName = "Existing", userAdded = false),
            ModelEntry(id = "new-model", displayName = "New", userAdded = false),
        )
        val merged = ModelCatalogMerge.merge(existing, fresh)
        assertEquals(2, merged.size)
        assertEquals("existing-model", merged[0].id)
        assertEquals("new-model", merged[1].id)
    }

    @Test
    fun `complex scenario with user-added, modified pricing, removed, and new models`() {
        val existing = listOf(
            ModelEntry(id = "user-custom", displayName = "Custom", userAdded = true, inputPerM = 100.0),
            ModelEntry(
                id = "common-model",
                displayName = "Common Old",
                userAdded = false,
                inputPerM = 10.0,
                outputPerM = 20.0,
            ),
            ModelEntry(id = "removed-model", displayName = "Removed", userAdded = false),
        )
        val fresh = listOf(
            ModelEntry(
                id = "common-model",
                displayName = "Common New",
                userAdded = false,
                inputPerM = 5.0,
                outputPerM = 15.0,
            ),
            ModelEntry(id = "new-model", displayName = "New", userAdded = false, inputPerM = 3.0),
        )
        val merged = ModelCatalogMerge.merge(existing, fresh)

        assertEquals(3, merged.size)

        // User-added comes first
        assertTrue(merged.any { it.id == "user-custom" && it.userAdded && it.inputPerM == 100.0 })

        // common-model: user pricing preserved, displayName updated
        val common = merged.find { it.id == "common-model" }!!
        assertEquals("Common New", common.displayName)
        assertEquals(10.0, common.inputPerM, 0.0001)
        assertEquals(20.0, common.outputPerM, 0.0001)

        // removed-model: dropped (not in fresh, not user-added)
        assertTrue(merged.none { it.id == "removed-model" })

        // new-model: added
        assertTrue(merged.any { it.id == "new-model" && it.inputPerM == 3.0 })
    }

    @Test
    fun `enabled flag is preserved from existing when pricing is not modified`() {
        val existing = listOf(
            ModelEntry(id = "model-1", enabled = false, inputPerM = 1.0),
        )
        val fresh = listOf(
            ModelEntry(id = "model-1", enabled = true, inputPerM = 1.0),
        )
        val merged = ModelCatalogMerge.merge(existing, fresh)
        assertEquals(1, merged.size)
        assertEquals(false, merged[0].enabled)
    }

    @Test
    fun `enabled flag is preserved from existing when pricing is modified`() {
        val existing = listOf(
            ModelEntry(id = "model-1", enabled = false, inputPerM = 10.0),
        )
        val fresh = listOf(
            ModelEntry(id = "model-1", enabled = true, inputPerM = 5.0),
        )
        val merged = ModelCatalogMerge.merge(existing, fresh)
        assertEquals(1, merged.size)
        assertEquals(false, merged[0].enabled)
    }

    @Test
    fun `existing row order is preserved without churn`() {
        val existing = listOf(
            ModelEntry(id = "b-model", displayName = "B", userAdded = false),
            ModelEntry(id = "user-row", displayName = "User", userAdded = true),
            ModelEntry(id = "a-model", displayName = "A", userAdded = false),
        )
        val fresh = listOf(
            ModelEntry(id = "a-model", displayName = "A", userAdded = false),
            ModelEntry(id = "b-model", displayName = "B", userAdded = false),
        )
        val merged = ModelCatalogMerge.merge(existing, fresh)
        // Original existing order is preserved; user-added row stays in place.
        assertEquals(listOf("b-model", "user-row", "a-model"), merged.map { it.id })
    }

    @Test
    fun `user-added model whose id reappears in fresh yields single row with user data`() {
        val existing = listOf(
            ModelEntry(id = "dup-model", displayName = "User Version", userAdded = true, inputPerM = 42.0),
        )
        val fresh = listOf(
            ModelEntry(id = "dup-model", displayName = "Provider Version", userAdded = false, inputPerM = 1.0),
        )
        val merged = ModelCatalogMerge.merge(existing, fresh)
        assertEquals(1, merged.size)
        assertEquals("dup-model", merged[0].id)
        assertTrue(merged[0].userAdded)
        assertEquals("User Version", merged[0].displayName)
        assertEquals(42.0, merged[0].inputPerM, 0.0001)
    }

    @Test
    fun `refresh relearns capability for provider (non-user-added) rows`() {
        // A refresh re-verifies each provider model, so the freshly-verified capability must win over
        // the stale value even when the user has customized pricing.
        val existing = listOf(
            ModelEntry(id = "provider-model", displayName = "Provider", userAdded = false, capability = "unknown", inputPerM = 10.0),
        )
        val fresh = listOf(
            ModelEntry(id = "provider-model", displayName = "Provider", userAdded = false, capability = "agentic", inputPerM = 1.0),
        )
        val merged = ModelCatalogMerge.merge(existing, fresh)
        assertEquals(1, merged.size)
        // Fresh capability wins; user pricing preserved.
        assertEquals("agentic", merged[0].capability)
        assertEquals(10.0, merged[0].inputPerM, 0.0001)
    }

    @Test
    fun `refresh keeps user-added rows capability untouched`() {
        val existing = listOf(
            ModelEntry(id = "user-model", displayName = "User", userAdded = true, capability = "agentic"),
        )
        // A same-id fresh row from the provider must not override the user-added row's capability.
        val fresh = listOf(
            ModelEntry(id = "user-model", displayName = "Provider", userAdded = false, capability = "completion_only"),
        )
        val merged = ModelCatalogMerge.merge(existing, fresh)
        assertEquals(1, merged.size)
        assertTrue(merged[0].userAdded)
        assertEquals("agentic", merged[0].capability)
    }

    @Test
    fun `duplicate ids within fresh collapse to a single appended row`() {
        val fresh = listOf(
            ModelEntry(id = "dup", displayName = "First", inputPerM = 1.0),
            ModelEntry(id = "dup", displayName = "Second", inputPerM = 2.0),
        )
        val merged = ModelCatalogMerge.merge(emptyList(), fresh)
        assertEquals(1, merged.size)
        assertEquals("dup", merged[0].id)
        // First occurrence wins.
        assertEquals("First", merged[0].displayName)
        assertEquals(1.0, merged[0].inputPerM, 0.0001)
    }
}
