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

/**
 * Merges freshly-fetched models with the existing catalog, preserving user-added rows,
 * user-modified pricing, and the existing row ORDER (no churn on repeated refreshes).
 * The merge strategy:
 * - Iterate the existing catalog once in its original order:
 *   - user-added rows (userAdded=true) are kept as-is (even if absent from fresh).
 *   - non-user-added rows present in fresh are refreshed (fresh metadata) but keep the
 *     user's pricing when it differs from fresh, and always keep the existing enabled flag.
 *   - non-user-added rows absent from fresh are dropped (removed by the provider).
 * - Then append genuinely-new fresh rows (ids not already present), first occurrence wins
 *   (duplicate ids within `fresh` collapse to a single row).
 *
 * Id matching is case-sensitive.
 */
object ModelCatalogMerge {
    fun merge(
        existing: List<ModelEntry>,
        fresh: List<ModelEntry>,
    ): List<ModelEntry> {
        val result = mutableListOf<ModelEntry>()
        val freshById = fresh.associateBy { it.id }
        val existingIds = existing.map { it.id }.toSet()
        val emitted = mutableSetOf<String>()

        // Pass 1: walk existing in original order, preserving user customizations.
        existing.forEach { existingEntry ->
            if (existingEntry.id in emitted) return@forEach
            if (existingEntry.userAdded) {
                result.add(existingEntry.copy())
                emitted.add(existingEntry.id)
                return@forEach
            }
            val freshEntry = freshById[existingEntry.id]
            if (freshEntry != null) {
                val userModifiedPricing = existingEntry.inputPerM != freshEntry.inputPerM ||
                    existingEntry.outputPerM != freshEntry.outputPerM ||
                    existingEntry.cachedInputPerM != freshEntry.cachedInputPerM ||
                    existingEntry.reasoningPerM != freshEntry.reasoningPerM
                // A user-set context window must survive a /models refresh (the endpoint never
                // reports it, so freshEntry.contextWindow is always 0). Preserve any non-zero value.
                val userContextWindow = existingEntry.contextWindow.takeIf { it > 0 } ?: freshEntry.contextWindow

                val merged = if (userModifiedPricing) {
                    // Keep user pricing, but refresh other fields (displayName, capability); keep enabled.
                    freshEntry.copy(
                        inputPerM = existingEntry.inputPerM,
                        outputPerM = existingEntry.outputPerM,
                        cachedInputPerM = existingEntry.cachedInputPerM,
                        reasoningPerM = existingEntry.reasoningPerM,
                        enabled = existingEntry.enabled,
                        contextWindow = userContextWindow,
                    )
                } else {
                    // No user override; use fresh entry entirely, but preserve enabled + context window.
                    freshEntry.copy(enabled = existingEntry.enabled, contextWindow = userContextWindow)
                }
                result.add(merged)
                emitted.add(existingEntry.id)
            }
            // If freshEntry is null, the model was removed from the provider — drop it.
        }

        // Pass 2: append genuinely-new fresh rows, first occurrence wins (dedup by id).
        fresh.forEach { freshEntry ->
            if (freshEntry.id in existingIds) return@forEach
            if (freshEntry.id in emitted) return@forEach
            result.add(freshEntry.copy())
            emitted.add(freshEntry.id)
        }

        return result
    }
}
