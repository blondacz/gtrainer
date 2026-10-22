package com.gtrainer

import kotlinx.serialization.encodeToString

/** Display-independent projections. No writes, queue admission, clinical inference or invented tolerances. */
internal object AdaptationFindingReasons {
    private val json=ScopedEvidencePreparation.json
    private fun hash(parts: List<String>)=AnalysisClaims.hash(json.encodeToString(parts))
    private fun subject(plan: PlanRef,kind: String,parts: List<String>)=hash(listOf(plan.planId,plan.revision.toString(),kind)+parts)
    private fun reason(kind: String,subject: String,condition: String,material: List<String>,dependencies: List<String>)=
        AdaptationReasonObservation(hash(listOf(kind,subject)),kind,subject,condition,hash(material),hash(dependencies))

    fun constraintReasonId(eventId: String)=hash(listOf("changed_constraint",hash(listOf("constraint_event",eventId))))

    /** Code-owned source addresses, not a second finding evaluator or an admission receipt. */
    fun reconciliationProvenance(core: PlanReconciliationCore): Map<String,List<String>> = buildMap {
        for(session in core.sessions) {
            val observations=reconcile(core.copy(sessions=listOf(session)))
            observations.firstOrNull()?.let { put(it.reasonId,listOf("plan_session/${session.sessionId}")) }
            observations.drop(1).zip(session.targetChecks.sortedBy { it.targetIndex }).forEach { (finding,check) ->
                put(finding.reasonId,listOfNotNull("plan_session/${session.sessionId}","planned_target/${session.sessionId}/${check.targetIndex}",
                    check.activityId?.let { "activity/$it" },check.effortReport?.let { "effort_report/${it.reportId}/${it.revision}" }))
            }
        }
    }

    /** One opaque canonical event is one finding, including unpolled A→B→A changes.
     * Losing eligibility is not a new event or a positive clearing/retrigger. */
    fun constraints(capture: CapturedConstraintChanges?, projected: ProjectedConstraintChanges): List<AdaptationReasonObservation> {
        if(capture==null || !capture.available || !projected.available) return emptyList()
        val eligible=projected.eligibleEventIds.toSet()
        return capture.events.map { event ->
            val subject=hash(listOf("constraint_event",event.eventId))
            reason("changed_constraint",subject,if(event.eventId in eligible) "active" else "provisional",
                listOf("canonical_event",event.eventId),listOf(json.encodeToString(event.proof),(event.eventId in eligible).toString()))
        }
    }

    fun reconcile(core: PlanReconciliationCore): List<AdaptationReasonObservation> {
        val plan=core.selection.selected ?: return emptyList()
        require(core.sessions.size<=1000)
        return core.sessions.sortedBy { it.sessionId }.flatMap { finding ->
            val session=subject(plan,"session",listOf(finding.sessionId))
            val dependency=listOf(json.encodeToString(finding),json.encodeToString(core.coverage.sortedBy { it.category }.map {
                listOf(it.category,it.readStatus,it.rejected.toString(),it.incomplete.toString())
            }))
            val skipCondition=when {
                finding.state=="confirmed_skipped" -> "active"
                finding.state=="matched" || finding.state=="omitted" -> "clear"
                finding.state=="needs_confirmation" || finding.state=="ambiguous" -> "needs_confirmation"
                else -> "provisional"
            }
            val skip=reason("confirmed_skip",session,skipCondition,listOf(finding.state),dependency)
            val targets=finding.targetChecks.sortedBy { it.targetIndex }.map { check ->
                require(check.plan==plan && check.sessionId==finding.sessionId)
                val target=subject(plan,"target",listOf(finding.sessionId,check.targetIndex.toString(),json.encodeToString(check.target)))
                val condition=when {
                    finding.state=="omitted" -> "clear"
                    finding.state!="matched" -> if(finding.state in setOf("needs_confirmation","ambiguous")) "needs_confirmation" else "provisional"
                    check.status in setOf("below_range","above_range") -> "active"
                    check.status=="within_range" -> "clear"
                    else -> "unevaluable"
                }
                reason("planned_range",target,condition,listOf(check.status,check.reason,json.encodeToString(check.actualValue),
                    json.encodeToString(check.target)),listOf(json.encodeToString(check))+dependency)
            }
            listOf(skip)+targets
        }
    }

    /** Rule/activity lineage stays stable across corrections and rolling display windows. */
    fun workload(plan: PlanRef,report: ExtraWorkloadReport,priorContributors: Set<String> = emptySet()): List<AdaptationReasonObservation> {
        require(priorContributors.size<=50_000 && priorContributors.all { it.matches(Regex("[a-f0-9]{64}")) })
        val config=report.rule.configuration ?: return emptyList()
        if(!report.rule.enabled) return emptyList() // Disablement is not a positively evaluated clearing.
        val selector=listOf(config.aggregation,config.metric,config.unit,config.basis,config.sport,config.source,
            config.upstreamOrigin?:"",config.timeZone,config.windowDays.toString(),config.comparator,config.threshold.toString())
        fun condition(status: String)=when(status) { "triggered" -> "active";"clear" -> "clear";"needs_confirmation" -> "needs_confirmation";else -> "unevaluable" }
        val dependencies=listOf(json.encodeToString(report))
        return if(config.aggregation=="cumulative") {
            val subject=subject(plan,"extra_workload",listOf(report.rule.ruleId,"cumulative"))
            val positivelyMatched=report.items.filter { it.status=="clear" && it.reason=="matched_to_selected_plan" }.map { it.activity.activityId }.toSet()
            val positivelyEvaluated=positivelyMatched+report.supportingActivityIds
            val missingPrior=priorContributors.any { it !in positivelyEvaluated }
            val positiveMatchClearing=priorContributors.isNotEmpty() && positivelyMatched.containsAll(priorContributors)
            val condition=if(report.status=="clear" && (missingPrior || report.supportingActivityIds.isEmpty() && !positiveMatchClearing)) "provisional" else condition(report.status)
            listOf(reason("extra_workload",subject,condition,selector+listOf(report.value.toString()),dependencies))
        } else report.items.map { item ->
            val subject=subject(plan,"extra_workload",listOf(report.rule.ruleId,"per_record",item.activity.activityId))
            val condition=if(item.reason=="outside_configured_window") "provisional" else condition(item.status)
            reason("extra_workload",subject,condition,selector+listOf(item.value.toString(),item.status),listOf(json.encodeToString(report.rule),json.encodeToString(item)))
        }
    }
}
