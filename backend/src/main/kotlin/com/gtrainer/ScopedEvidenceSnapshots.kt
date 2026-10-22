package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Instant

@Serializable
data class ScopedEvidenceDependency(val kind: String, val identity: String, val revision: Int)

@Serializable
data class ScopedEvidenceManifest(
    val scope: ResolvedReviewScope, val included: List<String>, val displayOnly: List<String>,
    val excluded: List<String>, val missing: List<String>, val needsConfirmation: List<String>,
    val dependencies: List<ScopedEvidenceDependency>, val omitted: ScopedEvidenceOmissions,
    val blockedReasons: List<String>, val packetBytes: Int,
    val baselines: List<ScopedWellnessBaseline> = emptyList(),
    val displayOnlyRecordCount: Int = displayOnly.size,
    val detailPolicy: String = "bounded_manifest_candidates_in_authenticated_record_pages",
) { override fun toString()="ScopedEvidenceManifest([REDACTED])" }

@Serializable
data class ScopedEvidenceSnapshot(
    val snapshotId: String, val createdUtc: String, val packetSha256: String?, val state: String,
    val packet: ScopedEvidencePacket?, val manifest: ScopedEvidenceManifest,
    val admission: String = "closed_not_qualified_not_execution_bound",
) { override fun toString()="ScopedEvidenceSnapshot([REDACTED])" }

@Serializable
data class ScopedEvidencePrepared(val report: ScopedTrendReport, val snapshot: ScopedEvidenceSnapshot) {
    override fun toString()="ScopedEvidencePrepared([REDACTED])"
}

@Serializable
data class ScopedEvidenceRecord(val category: String, val source: String, val sourceRecordId: String,
    val importedRevision: Long?, val status: String, val activity: ActivityRecord?, val wellness: WellnessRecord?,val roles: List<String>) {
    override fun toString()="ScopedEvidenceRecord([REDACTED])"
}

@Serializable
data class ScopedEvidenceRecords(val snapshotId: String, val items: List<ScopedEvidenceRecord>, val nextOffset: Int?,
    val limitation: String = "snapshot_membership_only_changed_records_not_replayed_as_original_evidence") {
    override fun toString()="ScopedEvidenceRecords([REDACTED])"
}

internal data class PreparedScopedEvidence(
    val request: ScopedEvidenceRequest, val captured: CapturedScopedEvidence,
    val packet: ScopedEvidencePacket, val bounded: ScopedBoundedEvidence, val manifest: ScopedEvidenceManifest,
    val bindingSha256: String, val requestJson: String, val packetJson: String?, val manifestJson: String,
    val records: List<PreparedScopedRecord>,
) { override fun toString()="PreparedScopedEvidence([REDACTED])" }

internal data class PreparedScopedRecord(val category: String,val source: String,val sourceRecordId: String,
    val revision: Long?,val recordSha256: String,val rolesJson: String) { override fun toString()="PreparedScopedRecord([REDACTED])" }

internal fun prepareScopedEvidence(request: ScopedEvidenceRequest, captured: CapturedScopedEvidence,
    report: ScopedTrendReport,packetOverride: ScopedEvidencePacket? = null): PreparedScopedEvidence {
    val packet=packetOverride ?: ScopedEvidencePreparation.packet(request,captured,report)
    val bounded=ScopedEvidenceBudget.bound(packet)
    val context=(listOf(packet.context.evidence,packet.context.amendments).flatMap {
        it.mandatoryRestrictions+it.optionalContexts.map { view -> view.context }+it.mandatoryClarifications.map { view -> view.context }
    }).distinctBy { it.contextId to it.revision }
    val commitmentRefs=packet.commitments.flatMap { it.occurrences.map { occurrence -> occurrence.reference }+it.needsReconfirmation }.distinct()
    val dependencies=(context.map { ScopedEvidenceDependency("context",it.contextId,it.revision) }+
        packet.sourceBindings.map { ScopedEvidenceDependency("intake",it.intakeId,it.currentRevision) }+
        commitmentRefs.map { ScopedEvidenceDependency("commitment",it.commitmentId,it.revision) }+
         listOfNotNull(packet.plan.plan?.let { ScopedEvidenceDependency("plan",it.planId,it.revision) })+
         captured.adaptationReference?.let { reference -> reference.stored.snapshot.manifest.dependencies+
             ScopedEvidenceDependency("adaptation_result",reference.stored.work.key(),1) }.orEmpty())
        .distinct().sortedWith(compareBy({it.kind},{it.identity},{it.revision}))
    val facts=bounded.packet?.facts
    val included=(facts?.let { f -> f.sessions.map { it.evidenceId }+f.aggregates.map { it.evidenceId }+f.wellness.map { it.evidenceId } }.orEmpty()+
        bounded.packet?.baselines.orEmpty().flatMap { it.days }.flatMap { it.samples }.map { it.evidenceId }+
        bounded.packet?.latestAvailableWellness?.days.orEmpty().flatMap { it.observations }.map { it.evidenceId }+
        bounded.packet?.let { admitted ->
            listOf(admitted.context.evidence,admitted.context.amendments).flatMap { selection ->
                selection.mandatoryRestrictions+selection.optionalContexts.map { it.context }+selection.mandatoryClarifications.map { it.context }
            }.map { "context/${it.contextId}/${it.revision}" }+
                admitted.commitments.flatMap { it.occurrences }.map { "commitment_occurrence/${it.occurrenceId}/${it.reference.revision}" }+
                listOfNotNull(admitted.plan.plan?.let { "plan/${it.planId}/${it.revision}" })+
                admitted.plan.sessions.map { "plan_session/${it.sessionId}" }+
                admitted.baselines.indices.map { "baseline_configuration/$it" }+
                listOfNotNull(admitted.adaptation?.let { "adaptation_result/${it.workKey}" })
        }.orEmpty()).distinct()
    val display=if(captured.read.scope.wellnessPolicy=="display_only")
        ScopedReviewEvidence.membership(captured.read,includeDisplayOnly=true).wellness.map {
            "wellness_record/${scopedActivityId(it.source,it.sourceRecordId)}"
        } else emptyList()
    val needs=(packet.context.evidence.mandatoryClarifications+packet.context.amendments.mandatoryClarifications)
        .map { "context/${it.context.contextId}/${it.context.revision}" }+
        commitmentRefs.filter { ref -> packet.commitments.any { ref in it.needsReconfirmation } }.map { "commitment/${it.commitmentId}/${it.revision}" }
    val baselineInspection=packet.baselines.map { baseline -> baseline.copy(days=baseline.days.map { day ->
        day.copy(evidenceIds=emptyList(),samples=emptyList(),candidateDetails="authenticated_record_pages")
    }) }
    val manifest=ScopedEvidenceManifest(captured.read.scope,included,display.take(200),
        if(captured.read.scope.wellnessPolicy=="off") listOf("optional_wellness") else emptyList(),
        (packet.facts.unavailable.map { it.key }+packet.latestAvailableWellness?.missing.orEmpty()).distinct(),needs.distinct(),dependencies,bounded.omitted,bounded.blockedReasons,bounded.packetBytes,baselineInspection,display.size)
    val json=ScopedEvidencePreparation.json
    val membership=ScopedEvidencePreparation.membership(request,captured.read,includeDisplayOnly=true)
    val selectedWellness=ScopedReviewEvidence.membership(captured.read,includeDisplayOnly=true).wellness.map { it.source to it.sourceRecordId }.toSet()
    val latestWellness=packet.latestAvailableWellness?.days.orEmpty().flatMap { it.observations }.map { it.source to it.sourceRecordId }.toSet()
    val receipts=captured.read.provenance.associateBy { Triple(it.category,it.source,it.sourceRecordId) }
    fun member(category: String,source: String,id: String,encoded: String,roles: List<String>)=PreparedScopedRecord(category,source,id,
        receipts[Triple(category,source,id)]?.revision,AnalysisClaims.hash(encoded),json.encodeToString(roles))
    val records=(membership.activities.map { member("activities",it.source,it.sourceRecordId,json.encodeToString(it),listOf("assessment_subject")) }+
        membership.wellness.map { record ->
            val roles=buildList {
                if(record.source to record.sourceRecordId in selectedWellness)
                    add(if(captured.read.scope.wellnessPolicy=="display_only") "display_only" else "assessment_context")
                if(request.baselines.any { it.matches(record) }) add("baseline")
                if(record.source to record.sourceRecordId in latestWellness) add("latest_available_context_not_period_subject")
            }
            member("wellness",record.source,record.sourceRecordId,json.encodeToString(record),roles)
        }+captured.adaptationReference?.records.orEmpty()).distinctBy { Triple(it.category,it.source,it.sourceRecordId) }
    require(records.size<=50_000)
    val manifestJson=encodeScopedInspectionManifest(manifest)
    return PreparedScopedEvidence(request,captured,packet,bounded,manifest,ScopedEvidencePreparation.binding(packet,captured.read),
        json.encodeToString(request),bounded.packet?.let { json.encodeToString(it) },manifestJson,records)
}

internal const val MAX_SCOPED_INSPECTION_BYTES=2_097_152
internal fun encodeScopedInspectionManifest(manifest: ScopedEvidenceManifest): String =
    ScopedEvidencePreparation.json.encodeToString(manifest).also {
        if(it.toByteArray(Charsets.UTF_8).size>MAX_SCOPED_INSPECTION_BYTES) throw ScopedEvidenceInspectionLimit()
    }
class ScopedEvidenceInspectionLimit : IllegalStateException("Inspection manifest exceeds its local storage bound; factual reporting remains available")

class ScopedEvidenceConflict : IllegalStateException("Evidence changed during preparation; prepare again without inference")
class ScopedEvidenceNotFound : IllegalStateException("Evidence snapshot is unavailable or was removed")
