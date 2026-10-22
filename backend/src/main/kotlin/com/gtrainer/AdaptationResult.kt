package com.gtrainer

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class AdaptationReasonFact(val reasonId: String,val revision: Long,val kind: String,val condition: String,
    val provenance: List<String> = emptyList())

@Serializable
data class AdaptationSignalFact(val rule: PersonalSignalRule,val current: PersonalSignalDay?,val baselineDays: List<PersonalSignalSupportDay>) {
    override fun toString()="AdaptationSignalFact([REDACTED])"
}

@Serializable
data class AdaptationConstraintChangeFact(val eventId: String,val beforeContext: AthleteContext?,val afterContext: AthleteContext?,
    val beforeCommitment: CommitmentDefinition?,val afterCommitment: CommitmentDefinition?) {
    override fun toString()="AdaptationConstraintChangeFact([REDACTED])"
}

/** Provider-safe deterministic facts. No admission receipts, replay state, mutation journal or execution authority. */
@Serializable
data class AdaptationFacts(val workKey: String,val family: AdaptationFamily,val horizon: ScopedDateRange,
    val sessions: List<PlanSessionFinding>,val workload: List<ExtraWorkloadReport>,val signals: List<AdaptationSignalFact>,
    val constraintChanges: List<AdaptationConstraintChangeFact>,val reasons: List<AdaptationReasonFact>,
    val version: String=VERSION,val limitation: String="factual_preparation_only_no_amendment_decision_no_medical_clearance") {
    override fun toString()="AdaptationFacts([REDACTED])"
    companion object {
        const val VERSION="adaptation_facts_v2"
        internal fun from(prepared: PreparedSignalAdaptation): AdaptationFacts {
            val work=requireNotNull(prepared.work) { "Adaptation causal proof is unavailable" }
            val projection=prepared.projection
            val events=prepared.capture.root.constraintChanges?.events.orEmpty().filter { it.eventId in projection.constraintChanges.eligibleEventIds }
            val provenance=AdaptationFindingReasons.reconciliationProvenance(projection.reconciliation).toMutableMap()
            events.forEach { provenance[AdaptationFindingReasons.constraintReasonId(it.eventId)]=listOf("constraint_event/${it.eventId}") }
            prepared.supports.filter { it.kind=="extra_workload" }.forEach { support ->
                provenance[support.reasonId]=listOfNotNull(support.ruleId?.let { "workload_rule/$it" })+support.activityIds.map { "activity/$it" }
            }
            prepared.signals.forEach { signal ->
                val rule=signal.capture.signal.rule
                val sequence=signal.capture.stored.highWater?.episodeSequence ?: 0
                listOf(sequence,if(sequence<Long.MAX_VALUE) sequence+1 else sequence).filter { it>0 }.forEach {
                    provenance[PreparedSignalAdaptation.reasonId(rule.ruleId,it)]=listOf("signal_rule/${rule.ruleId}/${rule.revision}")
                }
            }
            return AdaptationFacts(work.key(),work.family,work.horizon,projection.reconciliation.sessions,
                projection.workload,prepared.signals.map { signal ->
                    val current=signal.report.days.lastOrNull()
                    AdaptationSignalFact(signal.report.rule,current,signal.report.support.filter {
                        current!=null && it.date in current.baselineOldest..current.baselineNewest
                    })
                },events.map { event ->
                    AdaptationConstraintChangeFact(event.eventId,event.proof.before?.context?.context,event.proof.after?.context?.context,
                        event.proof.before?.commitment?.definition,event.proof.after?.commitment?.definition)
                },prepared.heads.filter { head -> work.reasons.any { it.reasonId==head.reasonId } }.map {
                    AdaptationReasonFact(it.reasonId,it.revision,it.kind,it.condition,provenance[it.reasonId].orEmpty())
                })
        }
    }
}

internal data class CapturedAdaptationResult(val adaptation: CapturedSignalAdaptation,val evidence: CapturedScopedEvidence,
    val request: ScopedEvidenceRequest,val job: ReviewQueueJob?,val authorizationJob: ReviewQueueJob?=job) {
    override fun toString()="CapturedAdaptationResult([REDACTED])"
}

internal class PreparedAdaptationResult private constructor(val capture: CapturedAdaptationResult,val admission: PreparedSignalAdaptation,
    val evidence: PreparedScopedEvidence) {
    companion object {
        fun prepare(capture: CapturedAdaptationResult): PreparedAdaptationResult {
            val admission=PreparedSignalAdaptation.prepare(capture.adaptation)
            val facts=AdaptationFacts.from(admission)
            val enriched=capture.evidence.copy(adaptationFacts=facts)
            val read=enriched.read
            val root=capture.adaptation.root
            val report=ScopedTrends.report(read.scope,read.history,read.statuses)
            val base=ScopedEvidencePreparation.packet(capture.request,enriched,report)
            val relevant=admission.projection.constraints.filter { it.relevant && it.kind=="context" }.map { it.id }.toSet()
            val relevantCommitments=admission.projection.constraints.filter { it.relevant && it.kind=="commitment" }.map { it.id }.toSet()
            val commitments=root.commitments.filter { it.reference.commitmentId in relevantCommitments }
            val contexts=retrieveScopedAthleteContext(root.contexts.filter { it.context.contextId in relevant },ScopedContextRetrievalQuery(
                observationCutoff=Instant.parse(read.scope.asOf).atZone(java.time.ZoneId.of(facts.family.timeZone)).toLocalDate(),evidenceTargets=emptyList(),
                amendmentTargets=listOf(ContextRetrievalTarget(java.time.LocalDate.parse(facts.horizon.oldest),java.time.LocalDate.parse(facts.horizon.newest))),
                optionalLimit=1000,mandatoryLimit=1000))
            val usedContexts=(contexts.evidence.mandatoryRestrictions+contexts.evidence.mandatoryClarifications.map { it.context }+
                contexts.evidence.optionalContexts.map { it.context }+contexts.amendments.mandatoryRestrictions+
                contexts.amendments.mandatoryClarifications.map { it.context }+contexts.amendments.optionalContexts.map { it.context })
                .map { it.contextId to it.revision }.toSet()
            val links=root.contextLinks.filter { it.contextId to it.contextRevision in usedContexts }
            val calendars=listOf(projectCommitments(commitments,facts.horizon.oldest,facts.horizon.newest,facts.family.timeZone))
            val commitmentSources=calendars.flatMap { calendar ->
                calendar.occurrences.map { it.reference }+calendar.needsReconfirmation
            }.map { it.intakeId to it.intakeRevision }
            val sourceBindings=(links.map { it.intakeId to it.intakeRevision }+commitmentSources).distinct()
                .sortedWith(compareBy({it.first},{it.second})).map { (id,revision) ->
                    ScopedSourceBinding(id,revision,root.sourceHeads.getValue(id))
                }
            val packet=base.copy(facts=base.facts.copy(sessions=emptyList(),aggregates=emptyList(),wellness=emptyList(),
                sessionComparisons=emptyList(),periodComparisons=emptyList(),sourceStatus=emptyList(),unavailable=emptyList(),
                limitations=listOf("adaptation_findings_are_subject_not_display_period")),context=contexts,
                contextLinks=links,sourceBindings=sourceBindings,commitments=calendars,
                baselines=emptyList(),latestAvailableWellness=null)
            var prepared=prepareScopedEvidence(capture.request,enriched,report,packet)
            val endpoints=root.constraintChanges?.events.orEmpty().filter { it.eventId in admission.projection.constraintChanges.eligibleEventIds }
                .flatMap { listOfNotNull(it.proof.before,it.proof.after) }
            val proofDependencies=endpoints.flatMap { endpoint -> endpoint.heads.map { ref -> ScopedEvidenceDependency(ref.kind,ref.id,ref.revision) } }
            val dependencies=(prepared.manifest.dependencies+proofDependencies).distinct().sortedWith(compareBy({it.kind},{it.identity},{it.revision}))
            val json=ScopedEvidencePreparation.json
            val relevantActivities=(admission.projection.reconciliation.sessions.flatMap { it.candidateActivityIds+listOfNotNull(it.activityId,it.confirmation?.activity?.activityId) }+
                admission.projection.workload.flatMap { it.items }.map { it.activity.activityId }).toSet()
            val activities=root.reconciliation.records.filterKeys { it in relevantActivities }.values.toList()
            val wellness=capture.adaptation.replay.flatMap { it.history.wellness }.distinctBy { it.source to it.sourceRecordId }
            val receipts=capture.adaptation.replay.flatMap { it.provenance }.associateBy { Triple(it.category,it.source,it.sourceRecordId) }
            val extra=activities.map { record -> PreparedScopedRecord("activities",record.source,record.sourceRecordId,
                root.reconciliation.activities.singleOrNull { it.activityId==scopedActivityId(record) }?.revision,
                AnalysisClaims.hash(json.encodeToString(record)),json.encodeToString(listOf("adaptation_support"))) }+
                wellness.map { record -> PreparedScopedRecord("wellness",record.source,record.sourceRecordId,
                    receipts[Triple("wellness",record.source,record.sourceRecordId)]?.revision,
                    AnalysisClaims.hash(json.encodeToString(record)),json.encodeToString(listOf("adaptation_causal_support"))) }
            val records=extra.distinctBy { Triple(it.category,it.source,it.sourceRecordId) }
            require(records.size<=50_000)
            val manifest=prepared.manifest.copy(dependencies=dependencies)
            prepared=prepared.copy(manifest=manifest,manifestJson=encodeScopedInspectionManifest(manifest),records=records,
                bindingSha256=AnalysisClaims.hash(json.encodeToString(packet)+facts.workKey))
            return PreparedAdaptationResult(capture,admission,prepared)
        }
    }
    override fun toString()="PreparedAdaptationResult([REDACTED])"
}

internal data class StoredAdaptationResult(val work: AdaptationWorkVersion,val snapshot: ScopedEvidenceSnapshot,val validUntil: Instant) {
    override fun toString()="StoredAdaptationResult([REDACTED])"
}

internal data class CapturedAdaptationReference(val stored: StoredAdaptationResult,val current: CapturedSignalAdaptation,
    val records: List<PreparedScopedRecord>) {
    fun requireCurrent() {
        require(current.root.at<stored.validUntil) { "Adaptation result expired" }
        val prepared=PreparedSignalAdaptation.prepare(current)
        require(prepared.work==stored.work && AdaptationFacts.from(prepared)==stored.snapshot.packet?.adaptation) { "Adaptation result changed" }
    }
    override fun toString()="CapturedAdaptationReference([REDACTED])"
}
