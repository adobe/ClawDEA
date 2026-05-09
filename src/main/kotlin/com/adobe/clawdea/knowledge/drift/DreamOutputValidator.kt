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

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

object DreamOutputValidator {

    private val ALLOWED_ROOT_FIELDS = setOf("candidates")
    private val ALLOWED_CANDIDATE_FIELDS = setOf(
        "kind",
        "title",
        "targetFiles",
        "evidence",
        "usefulness",
        "contextCost",
        "confidence",
        "proposedAction",
        "patchPlan",
    )
    private val ALLOWED_EVIDENCE_FIELDS = setOf("type", "ref", "summary")

    private val KIND_BY_WIRE = mapOf(
        "missingConcept" to DreamCandidateKind.MISSING_CONCEPT,
        "staleConcept" to DreamCandidateKind.STALE_CONCEPT,
        "duplicateConcept" to DreamCandidateKind.DUPLICATE_CONCEPT,
        "indexCleanup" to DreamCandidateKind.INDEX_CLEANUP,
        "linkNormalization" to DreamCandidateKind.LINK_NORMALIZATION,
        "sourceReferenceFix" to DreamCandidateKind.SOURCE_REFERENCE_FIX,
    )
    private val EVIDENCE_TYPE_BY_WIRE = mapOf(
        "sourceRef" to DreamEvidenceType.SOURCE_REF,
        "sessionSignal" to DreamEvidenceType.SESSION_SIGNAL,
        "wikiProbeMiss" to DreamEvidenceType.WIKI_PROBE_MISS,
        "acceptedWikiChange" to DreamEvidenceType.ACCEPTED_WIKI_CHANGE,
        "staleLink" to DreamEvidenceType.STALE_LINK,
        "duplicateContent" to DreamEvidenceType.DUPLICATE_CONTENT,
    )
    private val CONTEXT_COST_BY_WIRE = mapOf(
        "shrinks-context" to DreamContextCost.SHRINKS_CONTEXT,
        "neutral" to DreamContextCost.NEUTRAL,
        "adds-context" to DreamContextCost.ADDS_CONTEXT,
    )
    private val CONFIDENCE_BY_WIRE = mapOf(
        "high" to DreamConfidence.HIGH,
        "medium" to DreamConfidence.MEDIUM,
        "low" to DreamConfidence.LOW,
    )
    private val ACTION_BY_WIRE = mapOf(
        "applyLowRisk" to DreamProposedAction.APPLY_LOW_RISK,
        "proposeDiff" to DreamProposedAction.PROPOSE_DIFF,
        "reportOnly" to DreamProposedAction.REPORT_ONLY,
    )

    fun validate(json: String): DreamValidationResult {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: JsonParseException) {
            return DreamValidationResult(emptyList(), listOf("Malformed JSON: ${e.message}"))
        } catch (e: IllegalStateException) {
            return DreamValidationResult(emptyList(), listOf("Malformed JSON: ${e.message}"))
        }

        if (!root.isJsonObject) {
            return DreamValidationResult(emptyList(), listOf("Dream output must be a JSON object"))
        }

        val rootObject = root.asJsonObject
        val rootErrors = mutableListOf<String>()
        validateAllowedFields(rootObject, ALLOWED_ROOT_FIELDS, "Dream output", rootErrors)
        val candidatesElement = rootObject.get("candidates")
        if (candidatesElement == null || !candidatesElement.isJsonArray) {
            return DreamValidationResult(emptyList(), listOf("Dream output must include a candidates array"))
        }
        if (rootErrors.isNotEmpty()) {
            return DreamValidationResult(emptyList(), rootErrors)
        }

        val errors = mutableListOf<String>()
        val candidates = mutableListOf<DreamCandidate>()
        candidatesElement.asJsonArray.forEachIndexed { index, element ->
            val candidate = validateCandidate(index, element, errors)
            if (candidate != null) candidates += candidate
        }
        return DreamValidationResult(candidates, errors)
    }

    private fun validateCandidate(index: Int, element: JsonElement, errors: MutableList<String>): DreamCandidate? {
        if (!element.isJsonObject) {
            errors += "Candidate $index must be an object"
            return null
        }

        val obj = element.asJsonObject
        val candidateErrors = mutableListOf<String>()
        validateAllowedFields(obj, ALLOWED_CANDIDATE_FIELDS, "Candidate $index", candidateErrors)
        val kind = readEnum(obj, "kind", KIND_BY_WIRE, "unsupported kind", index, candidateErrors)
        val title = readNonBlankString(obj, "title", index, candidateErrors)
        val targetFiles = readTargetFiles(obj, index, candidateErrors)
        val evidence = readEvidence(obj, index, candidateErrors)
        val usefulness = readNonBlankString(obj, "usefulness", index, candidateErrors)
        val contextCost = readEnum(obj, "contextCost", CONTEXT_COST_BY_WIRE, "unsupported contextCost", index, candidateErrors)
        val confidence = readEnum(obj, "confidence", CONFIDENCE_BY_WIRE, "unsupported confidence", index, candidateErrors)
        val proposedAction = readEnum(obj, "proposedAction", ACTION_BY_WIRE, "unsupported proposedAction", index, candidateErrors)
        val patchPlan = readNonBlankString(obj, "patchPlan", index, candidateErrors)
        validateProposedAction(kind, targetFiles, contextCost, confidence, proposedAction, index, candidateErrors)

        if (candidateErrors.isNotEmpty()) {
            errors += candidateErrors
            return null
        }

        return DreamCandidate(
            kind = kind!!,
            title = title!!,
            targetFiles = targetFiles!!,
            evidence = evidence!!,
            usefulness = usefulness!!,
            contextCost = contextCost!!,
            confidence = confidence!!,
            proposedAction = proposedAction!!,
            patchPlan = patchPlan!!,
        )
    }

    private fun readEvidence(obj: JsonObject, candidateIndex: Int, errors: MutableList<String>): List<DreamEvidence>? {
        val array = readArray(obj, "evidence", candidateIndex, errors) ?: return null
        if (array.size() == 0) {
            errors += "Candidate $candidateIndex evidence must not be empty"
            return null
        }

        val evidence = mutableListOf<DreamEvidence>()
        array.forEachIndexed { evidenceIndex, element ->
            if (!element.isJsonObject) {
                errors += "Candidate $candidateIndex evidence $evidenceIndex must be an object"
                return@forEachIndexed
            }
            val evidenceObj = element.asJsonObject
            validateAllowedFields(
                evidenceObj,
                ALLOWED_EVIDENCE_FIELDS,
                "Candidate $candidateIndex evidence $evidenceIndex",
                errors,
            )
            val type = readEnum(
                evidenceObj,
                "type",
                EVIDENCE_TYPE_BY_WIRE,
                "unsupported evidence type",
                candidateIndex,
                errors,
                " evidence $evidenceIndex",
            )
            val ref = readNonBlankString(evidenceObj, "ref", candidateIndex, errors, " evidence $evidenceIndex")
            val summary = readNonBlankString(evidenceObj, "summary", candidateIndex, errors, " evidence $evidenceIndex")
            if (type != null && ref != null && summary != null) {
                evidence += DreamEvidence(type, ref, summary)
            }
        }

        return if (evidence.size == array.size()) evidence else null
    }

    private fun readTargetFiles(obj: JsonObject, candidateIndex: Int, errors: MutableList<String>): List<String>? {
        val targetFiles = readNonEmptyStringArray(obj, "targetFiles", candidateIndex, errors) ?: return null
        val invalidTargets = targetFiles.filterNot { isSafeWikiTargetFile(it) }
        invalidTargets.forEach {
            errors += "Candidate $candidateIndex targetFiles entry must be a relative .claude/wiki/ path without traversal: $it"
        }
        return if (invalidTargets.isEmpty()) targetFiles else null
    }

    private fun isSafeWikiTargetFile(path: String): Boolean {
        if (!path.startsWith(".claude/wiki/")) return false
        if (path.contains("..")) return false
        if (path.contains("\\")) return false
        if (path.startsWith("/") || path.startsWith("\\") || WINDOWS_ABSOLUTE_PATH_RX.matches(path)) return false
        if (path == ".claude/wiki/") return false
        if (!path.endsWith(".md")) return false
        return true
    }

    private fun validateProposedAction(
        kind: DreamCandidateKind?,
        targetFiles: List<String>?,
        contextCost: DreamContextCost?,
        confidence: DreamConfidence?,
        proposedAction: DreamProposedAction?,
        candidateIndex: Int,
        errors: MutableList<String>,
    ) {
        if (proposedAction != DreamProposedAction.APPLY_LOW_RISK) return
        val validLowRisk = kind == DreamCandidateKind.LINK_NORMALIZATION &&
            confidence == DreamConfidence.HIGH &&
            contextCost != DreamContextCost.ADDS_CONTEXT &&
            targetFiles?.size == 1 &&
            isSafeWikiTargetFile(targetFiles.single())
        if (!validLowRisk) {
            errors += "Candidate $candidateIndex applyLowRisk is only valid for one high-confidence linkNormalization markdown wiki target"
        }
    }

    private fun validateAllowedFields(
        obj: JsonObject,
        allowedFields: Set<String>,
        location: String,
        errors: MutableList<String>,
    ) {
        val unknownFields = obj.keySet().filterNot { it in allowedFields }
        unknownFields.forEach { errors += "$location contains unknown field: $it" }
    }

    private fun readNonEmptyStringArray(
        obj: JsonObject,
        field: String,
        candidateIndex: Int,
        errors: MutableList<String>,
    ): List<String>? {
        val array = readArray(obj, field, candidateIndex, errors) ?: return null
        if (array.size() == 0) {
            errors += "Candidate $candidateIndex $field must not be empty"
            return null
        }
        val values = mutableListOf<String>()
        array.forEachIndexed { itemIndex, element ->
            val value = element.asStringOrNull()?.trim()
            if (value.isNullOrEmpty()) {
                errors += "Candidate $candidateIndex $field[$itemIndex] must not be blank"
            } else {
                values += value
            }
        }
        return if (values.size == array.size()) values else null
    }

    private fun readArray(obj: JsonObject, field: String, candidateIndex: Int, errors: MutableList<String>): JsonArray? {
        val element = obj.get(field)
        if (element == null || !element.isJsonArray) {
            errors += "Candidate $candidateIndex $field must be an array"
            return null
        }
        return element.asJsonArray
    }

    private fun readNonBlankString(
        obj: JsonObject,
        field: String,
        candidateIndex: Int,
        errors: MutableList<String>,
        location: String = "",
    ): String? {
        val value = obj.get(field).asStringOrNull()?.trim()
        if (value.isNullOrEmpty()) {
            errors += "Candidate $candidateIndex$location $field must not be blank"
            return null
        }
        return value
    }

    private fun <T> readEnum(
        obj: JsonObject,
        field: String,
        allowed: Map<String, T>,
        unsupportedLabel: String,
        candidateIndex: Int,
        errors: MutableList<String>,
        location: String = "",
    ): T? {
        val wireValue = obj.get(field).asStringOrNull()
        val parsed = allowed[wireValue]
        if (parsed == null) {
            errors += "Candidate $candidateIndex$location $unsupportedLabel: $wireValue"
        }
        return parsed
    }

    private fun JsonElement?.asStringOrNull(): String? =
        if (this != null && isJsonPrimitive && asJsonPrimitive.isString) asString else null

    private val WINDOWS_ABSOLUTE_PATH_RX = Regex("""^[A-Za-z]:[\\/].*""")
}
