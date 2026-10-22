package com.gtrainer

import kotlinx.serialization.Serializable

/** Purgeable reevaluation metadata. Admission and episode identity never depend on this payload surviving. */
@Serializable
internal data class AdaptationReasonSupport(val reasonId: String,val family: AdaptationFamily,val kind: String,
    val ruleId: String?=null,val activityIds: List<String> = emptyList(),val contributors: List<String> = emptyList()) {
    init {
        require(reasonId.matches(Regex("[a-f0-9]{64}")))
        require(kind in setOf("confirmed_skip","planned_range","extra_workload","changed_constraint"))
        ruleId?.let(::validateContextId)
        for(ids in listOf(activityIds,contributors)) require(ids.size<=50_000 && ids==ids.distinct().sorted() && ids.all { it.matches(Regex("[a-f0-9]{64}")) })
    }
    override fun toString()="AdaptationReasonSupport([REDACTED])"
}

internal object AdaptationSupports {
    fun project(capture: CapturedAdaptationEvidence,projection: PreparedAdaptationProjection): List<AdaptationReasonSupport> {
        val family=projection.family ?: return emptyList()
        val old=capture.reasonSupports.filter { it.family==family }.associateBy { it.reasonId }
        val supports=mutableMapOf<String,AdaptationReasonSupport>()
        for(observation in AdaptationFindingReasons.reconcile(projection.reconciliation))
            supports[observation.reasonId]=AdaptationReasonSupport(observation.reasonId,family,observation.kind)
        for(observation in AdaptationFindingReasons.constraints(capture.constraintChanges,projection.constraintChanges))
            supports[observation.reasonId]=AdaptationReasonSupport(observation.reasonId,family,observation.kind)
        for(report in projection.workload) {
            val prior=old.values.filter { it.ruleId==report.rule.ruleId }
            val contributors=prior.flatMap { it.contributors }.toSet()
            val observations=AdaptationFindingReasons.workload(family.plan,report,contributors)
            val cumulative=report.rule.configuration?.aggregation=="cumulative"
            for((index,observation) in observations.withIndex()) {
                val previous=old[observation.reasonId]
                val subjects=if(cumulative) report.items.map { it.activity.activityId } else listOf(report.items[index].activity.activityId)
                val retained=((previous?.activityIds.orEmpty())+subjects).distinct().sorted()
                val nextContributors=when(observation.condition) {
                    "clear" -> emptyList()
                    "active" -> (previous?.contributors.orEmpty()+if(cumulative) report.supportingActivityIds else subjects).distinct().sorted()
                    else -> previous?.contributors.orEmpty()
                }
                supports[observation.reasonId]=AdaptationReasonSupport(observation.reasonId,family,observation.kind,report.rule.ruleId,retained,nextContributors)
            }
        }
        // Retain missing subjects for reevaluation, but never treat disappearance as positive clearing.
        old.forEach { (id,support) -> supports.putIfAbsent(id,support) }
        return supports.values.sortedBy { it.reasonId }
    }
}
