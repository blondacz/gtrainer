package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest

@Serializable
data class PreparedComparison(
    val id: String, val sport: String?, val metric: String, val unit: String,
    val previousOldest: String, val previousNewest: String, val currentOldest: String, val currentNewest: String,
    val previousValue: Double?, val currentValue: Double?, val preparedState: String, val flags: List<String>,
) { override fun toString() = "PreparedComparison([REDACTED])" }

@Serializable
data class ModelPacket(val evidence: List<PreparedComparison>) {
    override fun toString() = "ModelPacket([REDACTED])"
}

internal data class AnalysisSnapshot(val input: AnalysisInput, val packet: ModelPacket, val packetSha256: String,
                                     val supports: Map<String, Pair<SummaryFact, SummaryFact>>) {
    override fun toString() = "AnalysisSnapshot([REDACTED])"
}

@Serializable
data class ClaimReference(val id: String, val state: String) {
    override fun toString() = "ClaimReference([REDACTED])"
}
@Serializable
data class TypedClaim(val kind: String, val evidence: List<ClaimReference>) {
    override fun toString() = "TypedClaim([REDACTED])"
}
@Serializable
data class TypedClaims(val observations: List<TypedClaim>) {
    override fun toString() = "TypedClaims([REDACTED])"
}
@Serializable
data class AnalysisObservation(val text: String, val evidenceIds: List<String>, val supportingMetrics: List<SummaryFact>) {
    override fun toString() = "AnalysisObservation([REDACTED])"
}

/** The only personal language admitted here is rendered from validated comparisons, never model prose. */
internal object AnalysisClaims {
    val json = Json { encodeDefaults = true }
    val kinds = listOf("co_occurrence", "sport_mix", "recorded_change", "unavailable_comparison")
    val states = listOf("increased", "decreased", "unchanged", "unavailable")
    val system = """Select descriptive typed observations from prepared comparisons. Return JSON only:
        observations contains one or two objects with kind and evidence; evidence entries have id and state.
        Copy preparedState exactly. Do no arithmetic. Output no prose, dates, values, scores or disclosures.
        co_occurrence connects at least one sport and one wellness metric over exactly the SAME two periods.
        sport_mix connects recorded movingTime for different sports over the SAME periods.
        recorded_change cites exactly one available comparison. unavailable_comparison cites only missing-value comparisons.
        Connect activity and wellness when both have usable comparisons. Cite EVERY supplied evidence ID across the observations,
        including missing comparisons using unavailable_comparison. Do not omit inconvenient or opposing directions.
        Valid IDs are only evidence record IDs, not field names. Directions may differ or be unchanged.
        Different periods cannot be connected. Missing measurements are unknown, not zero, illness or missed workouts.
        Do not infer recovery, health, causation, intensity, safety, readiness or workout prescriptions.
        The application supplies all personal prose, dates, numeric values, missing metrics and limitations.
    """.trimIndent()

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun prepare(input: AnalysisInput): AnalysisSnapshot {
        val supports = linkedMapOf<String, Pair<SummaryFact, SummaryFact>>()
        val comparisons = input.comparisons.filter { it.key == "movingTime" && it.sport != null }.take(2) +
            listOf("sleepSecs", "hrv").mapNotNull { key -> input.comparisons.singleOrNull { it.sport == null && it.key == key } }
        val evidence = comparisons.mapIndexed { index, comparison ->
            val before = input.facts.single { it.period == "previous" && it.sport == comparison.sport && it.metric == comparison.key }
            val after = input.facts.single { it.period == "current" && it.sport == comparison.sport && it.metric == comparison.key }
            require(before.value == comparison.previousValue && after.value == comparison.currentValue && before.unit == after.unit)
            val state = when {
                comparison.absoluteChange == null -> "unavailable"
                comparison.absoluteChange > 0 -> "increased"
                comparison.absoluteChange < 0 -> "decreased"
                else -> "unchanged"
            }
            val id = "e$index"
            supports[id] = before to after
            PreparedComparison(id, comparison.sport, comparison.key, comparison.unit, before.oldest, before.newest,
                after.oldest, after.newest, before.value, after.value, state, comparison.flags)
        }
        val packet = ModelPacket(evidence)
        return AnalysisSnapshot(input, packet, hash(json.encodeToString(packet)), supports)
    }

    fun sufficient(snapshot: AnalysisSnapshot): Boolean {
        fun usable(e: PreparedComparison): Boolean = e.preparedState != "unavailable" &&
            snapshot.supports.getValue(e.id).let { (before, after) -> before.sampleCount >= 2 && after.sampleCount >= 2 } &&
            "sparse_comparison" !in e.flags
        return snapshot.packet.evidence.any { it.sport != null && usable(it) } &&
            snapshot.packet.evidence.any { it.sport == null && usable(it) }
    }

    fun schema(packet: ModelPacket): JsonObject = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        putJsonArray("required") { add("observations") }
        putJsonObject("properties") {
            putJsonObject("observations") {
                put("type", "array"); put("minItems", 1); put("maxItems", 2)
                putJsonObject("items") {
                    put("type", "object"); put("additionalProperties", false)
                    putJsonArray("required") { add("kind"); add("evidence") }
                    putJsonObject("properties") {
                        putJsonObject("kind") { put("type", "string"); putJsonArray("enum") { kinds.forEach { add(it) } } }
                        putJsonObject("evidence") {
                            put("type", "array"); put("minItems", 1); put("maxItems", 3)
                            putJsonObject("items") {
                                put("type", "object"); put("additionalProperties", false)
                                putJsonArray("required") { add("id"); add("state") }
                                putJsonObject("properties") {
                                    putJsonObject("id") { put("type", "string"); putJsonArray("enum") { packet.evidence.forEach { add(it.id) } } }
                                    putJsonObject("state") { put("type", "string"); putJsonArray("enum") { states.forEach { add(it) } } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun validateAndRender(raw: String, snapshot: AnalysisSnapshot): List<AnalysisObservation>? = try {
        require(hash(json.encodeToString(snapshot.packet)) == snapshot.packetSha256)
        val claims = json.decodeFromJsonElement<TypedClaims>(StrictModelJson.parse(raw))
        require(claims.observations.size in 1..2)
        val byId = snapshot.packet.evidence.associateBy { it.id }
        val seen = mutableSetOf<Pair<String, List<String>>>()
        val selectedIds = mutableSetOf<String>()
        var connected = false
        val result = claims.observations.map { claim ->
            require(claim.kind in kinds && claim.evidence.size in 1..3)
            val ids = claim.evidence.map { it.id }
            selectedIds.addAll(ids)
            require(ids.distinct().size == ids.size && seen.add(claim.kind to ids.sorted()))
            val evidence = claim.evidence.map { ref ->
                require(ref.state in states)
                byId.getValue(ref.id).also { require(it.preparedState == ref.state) }
            }
            val samePeriods = evidence.map { listOf(it.previousOldest, it.previousNewest, it.currentOldest, it.currentNewest) }.distinct().size == 1
            val available = evidence.all { it.preparedState != "unavailable" }
            require(evidence.filter { it.preparedState != "unavailable" }.all {
                snapshot.supports.getValue(it.id).let { (a, b) -> a.sampleCount >= 2 && b.sampleCount >= 2 }
            })
            when (claim.kind) {
                "recorded_change" -> require(evidence.size == 1 && available)
                "unavailable_comparison" -> require(evidence.all { it.preparedState == "unavailable" })
                "co_occurrence" -> {
                    require(available && samePeriods && evidence.any { it.sport != null } && evidence.any { it.sport == null })
                    require(evidence.all { snapshot.supports.getValue(it.id).let { (a, b) -> a.sampleCount >= 2 && b.sampleCount >= 2 } })
                    connected = true
                }
                "sport_mix" -> require(available && samePeriods && evidence.all { it.metric == "movingTime" && it.sport != null } &&
                    evidence.map { it.sport }.distinct().size >= 2)
            }
            val supports = ids.flatMap { id -> snapshot.supports.getValue(id).let { listOf(it.first, it.second) } }
            val text = evidence.joinToString(" ") { e ->
                val fact = snapshot.supports.getValue(e.id).second
                val label = fact.label + if (fact.aggregation == "mean") " (mean of populated source records)" else " (sum of populated source records)"
                val value = if (e.preparedState == "unavailable") "comparison unavailable; missing measurements are unknown, not zero" else
                    "${e.preparedState}: ${e.previousValue} to ${e.currentValue} ${e.unit}"
                "${e.sport ?: "Wellness"} $label $value. Periods: ${e.previousOldest}–${e.previousNewest} and ${e.currentOldest}–${e.currentNewest}."
            } + when (claim.kind) {
                "co_occurrence" -> " These observed comparisons cover the same periods; they do not establish cause, recovery, or readiness."
                "sport_mix" -> " Recorded time in different sports is not equivalent effort or training stimulus."
                else -> ""
            }
            AnalysisObservation(text, supports.map { it.evidenceId }, supports)
        }
        require(connected) // Do not present a single-metric response as the requested cross-metric synthesis.
        require(selectedIds == byId.keys) // Never repair an incomplete selection or hide conflicting evidence.
        result
    } catch (_: Exception) { null } // Never propagate model output, metric values, or parsing diagnostics.
}
