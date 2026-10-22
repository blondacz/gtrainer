package com.gtrainer

import java.time.Instant

/** One owner snapshot. These local inputs, raw records and receipts are never provider DTOs. */
internal data class CapturedAdaptationEvidence(val at: Instant,val reconciliation: CapturedPlanReconciliation,
    val contexts: List<ContextLifecycleView>,val commitments: List<ConfirmedCommitment>,
    val contextLinks: List<ContextIntakeLink>,val sourceHeads: Map<String,Int>,
    val workloadRules: List<ExtraWorkloadRule>,val signals: List<CapturedPersonalSignal>,
    val reasonHeads: List<AdaptationReasonHead>,val admissions: List<AdaptationAdmission>,
    val removalGeneration: Long,val sourceRevision: Long,val totalChanges: Long,val dataVersion: Long,val ownerId: String,
    val timeZone: String,val constraintFrontier: ConstraintMutationFrontier,val constraintInventory: List<ConstraintSubjectRef>,
    val constraintChanges: CapturedConstraintChanges? = null,
    val reasonSupports: List<AdaptationReasonSupport> = emptyList()) {
    init { require(timeZone=="UTC" || timeZone in java.time.ZoneId.getAvailableZoneIds()) }
    override fun toString()="CapturedAdaptationEvidence([REDACTED])"
}

/** Staged factual projection only: no result reuse, admission acknowledgement or queue submission. */
internal data class PreparedAdaptationProjection(val captured: CapturedAdaptationEvidence,
    val family: AdaptationFamily?,val horizon: ScopedDateRange?,val reconciliation: PlanReconciliationCore,
    val workload: List<ExtraWorkloadReport>,val signals: List<PersonalSignalReport>,
    val constraints: List<AdaptationConstraintFingerprint>,val observations: List<AdaptationReasonObservation>,
    val projectedHeads: List<AdaptationReasonHead>,val temporalValidUntil: Instant,
    val constraintChanges: ProjectedConstraintChanges = ProjectedConstraintChanges(null,false,emptyList()),val missingIntegration: List<String> = listOf(
        "constraint_event_reason_admission","signal_checkpoint_replay_and_admission","atomic_reason_queue_admission",
        "durable_result_reuse","provider_packet_dependencies")) {
    override fun toString()="PreparedAdaptationProjection([REDACTED])"
}

internal object AdaptationProjection {
    fun prepare(capture: CapturedAdaptationEvidence): PreparedAdaptationProjection {
        require(capture.contexts.size<=1000 && capture.commitments.size<=1000 && capture.workloadRules.size<=20 && capture.signals.size<=20)
        require(capture.reconciliation.totalChanges==capture.totalChanges)
        require(capture.workloadRules.map { it.ruleId }.distinct().size==capture.workloadRules.size &&
            capture.signals.map { it.rule.ruleId }.distinct().size==capture.signals.size)
        require(capture.reasonHeads.size<=100_000 && capture.reasonHeads.map { it.reasonId }.distinct().size==capture.reasonHeads.size && capture.admissions.size<=100_000)
        require(capture.constraintInventory.size<=100_000 && capture.constraintInventory.map { it.kind to it.id }.distinct().size==capture.constraintInventory.size)
        require(capture.signals.sumOf { it.rawRecordCount.toLong() }+capture.reconciliation.records.size<=50_000)
        val core=PlanReconciler.core(capture.at,capture.reconciliation)
        val plan=capture.reconciliation.plan
        require(core.selection.selected==plan?.reference)
        val policy=plan?.definition?.policy
        val family=if(policy!=null) AdaptationFamily(plan.reference,policy.horizonDays,policy.adaptationTimeZone,plan.reference.planId) else null
        val horizon=policy?.horizon(capture.at)?.let { ScopedDateRange(it.first.toString(),it.second.toString()) }
        val workload=capture.workloadRules.sortedBy { it.ruleId }.map {
            val retained=capture.reasonSupports.filter { support -> support.family==family && support.ruleId==it.ruleId }.flatMap { support -> support.activityIds }.toSet()
            ExtraWorkloadRules.evaluate(it,capture.at,core,capture.reconciliation.records,retained)
        }
        val signals=capture.signals.sortedBy { it.rule.ruleId }.map {
            require(it.at==capture.at && it.ownerId==capture.ownerId && it.sourceRevision==capture.sourceRevision &&
                it.totalChanges==capture.totalChanges && it.dataVersion==capture.dataVersion)
            PersonalSignalRules.evaluate(it.rule,it.query,capture.at,it.samples).copy(sourceStatus=it.sourceStatus)
        }
        val constraints=plan?.let { AdaptationConstraintFingerprints.project(it,capture.at,capture.contexts,capture.commitments) }.orEmpty()
        val changedConstraints=ConstraintChanges.project(capture.constraintChanges,plan,capture.at)
        val observations=(AdaptationFindingReasons.constraints(capture.constraintChanges,changedConstraints)+AdaptationFindingReasons.reconcile(core)+core.selection.selected?.let { selected ->
            workload.flatMap { report -> AdaptationFindingReasons.workload(selected,report,capture.reasonSupports.filter {
                it.family==family && it.ruleId==report.rule.ruleId }.flatMap { it.contributors }.toSet()) }
        }.orEmpty()).sortedBy { it.reasonId }
        require(observations.size<=100_000 && observations.map { it.reasonId }.distinct().size==observations.size)
        val old=capture.reasonHeads.associateBy { it.reasonId }
        val heads=observations.map { AdaptationReasons.project(old[it.reasonId],it) }
        return PreparedAdaptationProjection(capture,family,horizon,core,workload,signals,constraints,observations,heads,
            AdaptationTemporalValidity.until(capture,capture.timeZone),changedConstraints)
    }
}
