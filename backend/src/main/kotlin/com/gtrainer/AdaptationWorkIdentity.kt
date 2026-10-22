package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Stable routing family; rolling dates, display scopes and daily purposes belong elsewhere. */
@Serializable
data class AdaptationFamily(val plan: PlanRef,val horizonDays: Int,val timeZone: String,
    val policyOwnerId: String) {
    init {
        require(horizonDays in 1..366 && (timeZone=="UTC" || timeZone in java.time.ZoneId.getAvailableZoneIds()))
        validateContextId(policyOwnerId)
        require(policyOwnerId==plan.planId)
    }
    fun key()=AnalysisClaims.hash(ScopedEvidencePreparation.json.encodeToString(this))
    override fun toString()="AdaptationFamily([REDACTED])"
}

@Serializable
data class AdaptationReasonRef(val reasonId: String,val revision: Long,val dependencySha256: String) {
    init { require(revision>0 && listOf(reasonId,dependencySha256).all { it.matches(Regex("[a-f0-9]{64}")) }) }
}

@Serializable
data class AdaptationPolicyRef(val kind: String,val ruleId: String,val revision: Int) {
    init { require(kind in setOf("personal_signal","extra_workload"));validateContextId(ruleId);require(revision in 1..1000) }
}

/** No admission times, job IDs, global import counters, routing purpose or display dates enter this hash. */
@Serializable
data class AdaptationWorkVersion(val family: AdaptationFamily,val horizon: ScopedDateRange,
    val reasons: List<AdaptationReasonRef>,val policies: List<AdaptationPolicyRef>,val evidenceDependencySha256: String,
    val evaluator: String="deterministic_adaptation_v1") {
    init {
        require(horizon.range().days==family.horizonDays)
        require(reasons.size<=100_000 && reasons.map { it.reasonId }.distinct().size==reasons.size)
        require(policies.size<=40 && policies.map { it.kind to it.ruleId }.distinct().size==policies.size)
        require(evidenceDependencySha256.matches(Regex("[a-f0-9]{64}")) && evaluator=="deterministic_adaptation_v1")
    }
    fun key()=AnalysisClaims.hash(ScopedEvidencePreparation.json.encodeToString(copy(
        reasons=reasons.sortedBy { it.reasonId },policies=policies.sortedWith(compareBy({it.kind},{it.ruleId})))))
    override fun toString()="AdaptationWorkVersion([REDACTED])"
}
