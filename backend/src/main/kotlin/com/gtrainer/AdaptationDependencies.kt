package com.gtrainer

import kotlinx.serialization.encodeToString

/** Canonical factual identity, separate from owner, queue and selection authorization. */
internal object AdaptationDependencies {
    private val json=ScopedEvidencePreparation.json
    fun digest(capture: CapturedAdaptationEvidence,projection: PreparedAdaptationProjection,signals: List<PreparedSignalReplay>): String {
        val sessions=projection.reconciliation.sessions.sortedBy { it.sessionId }
        val referenced=sessions.flatMap { it.candidateActivityIds+listOfNotNull(it.activityId,it.confirmation?.activity?.activityId) }.toSet()
        val activities=projection.reconciliation.activities.filter { it.activity.activityId in referenced }.sortedBy { it.activity.activityId }
        val coverage=projection.reconciliation.coverage.sortedBy { it.category }.map {
            listOf(it.category,it.readStatus,it.rejected.toString(),it.incomplete.toString())
        }
        val relevantContexts=projection.constraints.filter { it.relevant && it.kind=="context" }.map { it.id }.toSet()
        val links=capture.contextLinks.filter { it.contextId in relevantContexts }.sortedWith(compareBy({it.contextId},{it.contextRevision},{it.intakeId}))
        val relevantCommitments=projection.constraints.filter { it.relevant && it.kind=="commitment" }.map { it.id }.toSet()
        val intakeIds=links.map { it.intakeId }+capture.commitments.filter { it.reference.commitmentId in relevantCommitments }.map { it.reference.intakeId }
        val sourceHeads=intakeIds.distinct().sorted().map { listOf(it,capture.sourceHeads[it].toString()) }
        val parts=listOf(AdaptationFacts.VERSION,json.encodeToString(capture.reconciliation.plan),json.encodeToString(projection.horizon),
            json.encodeToString(sessions.map { listOf(it.sessionId,(it.schedule.startInstant()<=capture.at).toString(),(it.schedule.endInstant()<=capture.at).toString()) }),
            json.encodeToString(sessions),json.encodeToString(activities),json.encodeToString(coverage),
            json.encodeToString(links),json.encodeToString(sourceHeads),
            json.encodeToString(projection.workload.sortedBy { it.rule.ruleId }),
            json.encodeToString(signals.sortedBy { it.capture.signal.rule.ruleId }.map { signal ->
                listOf(json.encodeToString(signal.capture.signal.rule),json.encodeToString(signal.report.days),
                    json.encodeToString(signal.report.support),json.encodeToString(signal.checkpoint?.binding))
            }),json.encodeToString(projection.constraints.filter { it.relevant }.sortedWith(compareBy({it.kind},{it.id}))),
            json.encodeToString(capture.constraintChanges?.events?.filter { it.eventId in projection.constraintChanges.eligibleEventIds }?.sortedBy { it.eventId }?.map {
                listOf(it.eventId,json.encodeToString(it.proof))
            }))
        val encoded=json.encodeToString(parts)
        require(encoded.toByteArray(Charsets.UTF_8).size<=8*1024*1024) { "Adaptation dependency binding exceeds its byte budget" }
        return AnalysisClaims.hash(encoded)
    }
}
