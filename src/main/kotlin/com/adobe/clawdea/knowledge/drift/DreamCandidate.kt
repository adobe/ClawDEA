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
package com.adobe.clawdea.knowledge.drift

enum class DreamCandidateKind {
    MISSING_CONCEPT,
    STALE_CONCEPT,
    DUPLICATE_CONCEPT,
    INDEX_CLEANUP,
    LINK_NORMALIZATION,
    SOURCE_REFERENCE_FIX,
}

enum class DreamEvidenceType {
    SOURCE_REF,
    SESSION_SIGNAL,
    WIKI_PROBE_MISS,
    ACCEPTED_WIKI_CHANGE,
    STALE_LINK,
    DUPLICATE_CONTENT,
    USER_CORRECTION,
}

enum class DreamContextCost {
    SHRINKS_CONTEXT,
    NEUTRAL,
    ADDS_CONTEXT,
}

enum class DreamConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

enum class DreamProposedAction {
    APPLY_LOW_RISK,
    PROPOSE_DIFF,
    REPORT_ONLY,
}

data class DreamEvidence(
    val type: DreamEvidenceType,
    val ref: String,
    val summary: String,
)

data class DreamCandidate(
    val kind: DreamCandidateKind,
    val title: String,
    val targetFiles: List<String>,
    val evidence: List<DreamEvidence>,
    val usefulness: String,
    val contextCost: DreamContextCost,
    val confidence: DreamConfidence,
    val proposedAction: DreamProposedAction,
    val patchPlan: String,
)

data class DreamValidationResult(
    val candidates: List<DreamCandidate>,
    val errors: List<String>,
)
