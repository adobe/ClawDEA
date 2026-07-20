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

object ModelCatalogMerger {

    /**
     * Merges a freshly-probed catalog with the persisted one, preserving
     * userAdded entries on top and dropping fetched entries that collide
     * with a userAdded id. For non-userAdded entries, carries over
     * user-edited enablement, capability, and rates while refreshing
     * IDs/display names.
     */
    fun merge(existing: List<ModelEntry>, fetched: List<ModelEntry>): List<ModelEntry> {
        val userEntries = existing.filter { it.userAdded }
        val userIds = userEntries.mapTo(mutableSetOf()) { it.id }
        val existingById = existing.associateBy { it.id }

        val mergedFetched = fetched.filter { it.id !in userIds }.map { fresh ->
            val old = existingById[fresh.id]
            if (old != null && !old.userAdded) {
                // Carry over user-edited fields from existing entry
                fresh.copy(
                    enabled = old.enabled,
                    capability = old.capability,
                    inputPerM = old.inputPerM,
                    outputPerM = old.outputPerM,
                    cachedInputPerM = old.cachedInputPerM,
                    reasoningPerM = old.reasoningPerM,
                    // The /models endpoint never reports a context window, so a user-set value must
                    // survive refresh (fresh.contextWindow is always 0).
                    contextWindow = old.contextWindow,
                )
            } else {
                fresh
            }
        }

        return userEntries + mergedFetched
    }
}
