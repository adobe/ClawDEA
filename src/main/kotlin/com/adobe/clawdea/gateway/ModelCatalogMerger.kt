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
     * with a userAdded id.
     */
    fun merge(existing: List<ModelEntry>, fetched: List<ModelEntry>): List<ModelEntry> {
        val userEntries = existing.filter { it.userAdded }
        val userIds = userEntries.mapTo(mutableSetOf()) { it.id }
        return userEntries + fetched.filter { it.id !in userIds }
    }
}
