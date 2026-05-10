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
package com.adobe.clawdea.profiling.analysis

import com.adobe.clawdea.profiling.model.Recording
import com.adobe.clawdea.profiling.model.Source

class AnalysisService {

    private val recordings = mutableMapOf<String, Recording>()
    private val cache = mutableMapOf<String, Any>()

    fun register(id: String, recording: Recording) {
        recordings[id] = recording
    }

    fun get(id: String): Recording? = recordings[id]

    fun listRecordings(): Map<String, Recording> = recordings.toMap()

    fun analyzeCpu(recordingId: String, topN: Int = 50, threadFilter: String? = null): CpuHotspotResult {
        val recording = recordings[recordingId] ?: error("Recording '$recordingId' not found")
        val cacheKey = "cpu:$recordingId:$topN:$threadFilter"
        @Suppress("UNCHECKED_CAST")
        return cache.getOrPut(cacheKey) {
            CpuHotspotAnalyzer.analyze(recording, topN, threadFilter)
        } as CpuHotspotResult
    }

    fun analyzeAllocations(recordingId: String, topN: Int = 50, classFilter: String? = null): AllocationHotspotResult {
        val recording = recordings[recordingId] ?: error("Recording '$recordingId' not found")
        val cacheKey = "alloc:$recordingId:$topN:$classFilter"
        @Suppress("UNCHECKED_CAST")
        return cache.getOrPut(cacheKey) {
            AllocationHotspotAnalyzer.analyze(recording, topN, classFilter)
        } as AllocationHotspotResult
    }

    fun analyzeLeaks(recordingId: String, topN: Int = 50): HeapLeakResult {
        val recording = recordings[recordingId] ?: error("Recording '$recordingId' not found")
        if (recording.source != Source.IMPORTED_HPROF) {
            error("Leak analysis requires an hprof recording, but '$recordingId' is ${recording.source}")
        }
        val cacheKey = "leak:$recordingId:$topN"
        @Suppress("UNCHECKED_CAST")
        return cache.getOrPut(cacheKey) {
            HeapLeakResult(totalRetainedBytes = 0, topRetainers = emptyList())
        } as HeapLeakResult
    }
}
