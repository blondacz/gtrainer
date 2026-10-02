package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class FocusEvidence(val id: String, val sport: String?, val metric: String, val state: String) {
    override fun toString() = "FocusEvidence([REDACTED])"
}
@Serializable
data class FocusGroup(val id: String, val periods: ReviewPeriods, val coverage: String, val evidence: List<FocusEvidence>) {
    override fun toString() = "FocusGroup([REDACTED])"
}
@Serializable
data class FocusCandidate(val id: String, val kind: String, val groupId: String, val factIds: List<String>) {
    override fun toString() = "FocusCandidate([REDACTED])"
}
@Serializable
data class ReviewFocusPacket(val profile: String, val requestedFocus: String, val groups: List<FocusGroup>, val candidates: List<FocusCandidate>) {
    override fun toString() = "ReviewFocusPacket([REDACTED])"
}
@Serializable
data class ReviewInterpretation(val text: String, val kind: String, val groupId: String, val supportingFacts: List<ReviewFact>) {
    override fun toString() = "ReviewInterpretation([REDACTED])"
}
internal data class ReviewFocusSnapshot(val facts: FactualReview, val packet: ReviewFocusPacket, val packetSha256: String,
                                       val factsSha256: String) {
    override fun toString() = "ReviewFocusSnapshot([REDACTED])"
}
internal data class FocusValidation(val reason: String?, val interpretations: List<ReviewInterpretation> = emptyList())

/** Separately named app contract. Never overwrites the installed prototype or frozen benchmark prompts. */
internal object ReviewFocusProtocol {
    const val PROFILE = "review-focus-app-v1"
    val json = Json { encodeDefaults = true }
    val correctionReasons = setOf("invalid_json", "invalid_shape", "invalid_focus_count", "unsupported_focus", "duplicate_focus", "irrelevant_focus")
    val system = """Select one or two descriptive candidate IDs for the requestedFocus in this code-prepared packet.
        Return only {"focusIds":["candidate ID"]}, with no other fields or prose. Code already renders every factual comparison independently.
        daily_combined requires cross_metric_pattern; activity_balance requires sport_mix; missing_wellness requires coverage_gap.
        wellness requires wellness_pattern when available, otherwise coverage_gap. Never include unrelated candidates merely to cover facts.
        Candidate IDs refer to supported same-period groups. Missing and sparse measurements are unknown, not zero, illness or missed workouts.
        Do not infer cause, recovery, readiness, safety, medical conclusions, Garmin proprietary scores or workout prescriptions.
        No calculation, personal prose or additional facts are permitted. The application renders all text and support.
    """.trimIndent()

    fun prepare(input: AnalysisInput, focus: String): ReviewFocusSnapshot {
        // Deep-copy the request snapshot so even an injected provider cannot mutate the factual view.
        val facts = json.decodeFromString<FactualReview>(json.encodeToString(FactualReviews.prepare(input, focus)))
        val candidates = FactualReviews.candidates(facts.groups).mapIndexed { index, candidate ->
            FocusCandidate("f$index", candidate.kind, candidate.groupId, candidate.factIds.toList())
        }
        val packet = ReviewFocusPacket(PROFILE, focus, facts.groups.map { group ->
            FocusGroup(group.id, group.periods, group.coverage, group.facts.map { fact ->
                FocusEvidence(fact.id, fact.comparison.sport, fact.comparison.key, fact.state)
            })
        }, candidates)
        return ReviewFocusSnapshot(facts, packet, AnalysisClaims.hash(json.encodeToString(packet)), AnalysisClaims.hash(json.encodeToString(facts)))
    }

    fun unchanged(snapshot: ReviewFocusSnapshot): Boolean =
        AnalysisClaims.hash(json.encodeToString(snapshot.packet)) == snapshot.packetSha256 &&
            AnalysisClaims.hash(json.encodeToString(snapshot.facts)) == snapshot.factsSha256

    fun validate(raw: String, snapshot: ReviewFocusSnapshot): FocusValidation {
        if (!unchanged(snapshot)) return FocusValidation("evidence_changed")
        val value = try { StrictModelJson.parse(raw) } catch (_: Exception) { return FocusValidation("invalid_json") }
        if (value !is JsonObject || value.keys != setOf("focusIds")) return FocusValidation("invalid_shape")
        val choices = value["focusIds"] as? JsonArray ?: return FocusValidation("invalid_focus_count")
        if (choices.size !in 1..2 || choices.any { it !is JsonPrimitive || !it.isString }) return FocusValidation("invalid_focus_count")
        val ids = choices.map { it.jsonPrimitive.content }
        if (ids.distinct().size != ids.size) return FocusValidation("duplicate_focus")
        val selected = ids.map { id -> snapshot.packet.candidates.singleOrNull { it.id == id } ?: return FocusValidation("unsupported_focus") }
        val supported = FactualReviews.candidates(snapshot.facts.groups)
        val relevant = FactualReviews.select(supported, snapshot.facts.requestedFocus)
        val rendered = selected.map { candidate ->
            val focus = supported.singleOrNull { it.kind == candidate.kind && it.groupId == candidate.groupId && it.factIds == candidate.factIds }
                ?: return FocusValidation("evidence_changed")
            if (focus !in relevant) return FocusValidation("irrelevant_focus")
            val group = snapshot.facts.groups.single { it.id == focus.groupId }
            ReviewInterpretation(focus.text, focus.kind, focus.groupId, focus.factIds.map { id -> group.facts.single { it.id == id } })
        }
        return FocusValidation(null, rendered) // Whole response only; no repair, merging or deterministic substitution.
    }

    fun schema(packet: ReviewFocusPacket) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        putJsonArray("required") { add("focusIds") }
        putJsonObject("properties") {
            putJsonObject("focusIds") {
                put("type", "array"); put("minItems", 1); put("maxItems", 2)
                putJsonObject("items") { put("type", "string"); putJsonArray("enum") { packet.candidates.forEach { add(it.id) } } }
            }
        }
    }
}
