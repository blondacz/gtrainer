package com.gtrainer

import java.time.Clock
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

internal data class CapturedAmendmentSelection(val policy: CapturedPlanAmendment,val source: CapturedExecutionSource) {
    override fun toString()="CapturedAmendmentSelection([REDACTED])"
}

@Serializable
data class AmendmentRevisionBinding(val kind: String,val identity: String,val revision: Long)

@Serializable
data class AmendmentSelectionInspection(val status: String,val sourceSnapshotId: String,val sourcePacketSha256: String,
    val sourceBindingSha256: String,val adaptationWorkKey: String,val policy: PlanAmendmentInspection,
    val revisions: List<AmendmentRevisionBinding>,val packet: AmendmentSelectionPacketV1?,
    val exactPrompt: InterpretationPromptV1?,val exactCorrectionPrompt: InterpretationPromptV1?,val exactPromptBindingSha256: String?,
    val validUntilUtc: String,
    val limitation: String="local_inspection_not_inference_consent_or_approvable_proposal;boundary_only;mechanical_not_clinical;plan_unchanged") {
    override fun toString()="AmendmentSelectionInspection([REDACTED])"
}

@Serializable
data class CheckedAmendmentResult(val outcome: String,val preparation: AmendmentSelectionInspection,
    val selected: List<AmendmentCandidateV1>,val explanations: List<ScopedDescriptiveItem>,val issues: List<AmendmentPolicyIssue>,
    val checkedUtc: String,val expiresUtc: String,val resultSha256: String,
    val approval: String="not_approvable_no_persisted_proposal_no_plan_change") {
    override fun toString()="CheckedAmendmentResult([REDACTED])"
}

@Serializable
data class AmendmentValidationRequest(val snapshotId: String,val expectedPromptBindingSha256: String,val rawDraft: String) {
    override fun toString()="AmendmentValidationRequest([REDACTED])"
}

/** Frozen in-process handle. It owns no execution, persistence, approval or inference authority. */
internal class PreparedAmendmentSelection private constructor(private val capture: CapturedAmendmentSelection,
    private val inspectionJson: String,private val deadline: Instant) {
    fun inspection(): AmendmentSelectionInspection=json.decodeFromString(inspectionJson)

    fun finish(store: HistoryStore,clock: Clock): AmendmentSelectionInspection {
        store.finishAmendmentSelection(capture,deadline,clock)
        return inspection()
    }

    fun prepareProposal(store: HistoryStore,clock: Clock,raw: String,submission: String): PreparedProposalSave =
        PreparedProposalSave.fromDraft(this,store,clock,raw,submission)

    internal fun proposalCapture(): CapturedAmendmentSelection = capture

    fun validate(store: HistoryStore,clock: Clock,raw: String): CheckedAmendmentResult {
        val now=clock.instant()
        if(now<capture.policy.at || now>=deadline) throw PlanConflict()
        val prepared=inspection()
        val packet=requireNotNull(prepared.packet) { "No candidate packet; factual status is not an inferred no-change decision" }
        var outcome="rejected"
        var selected=emptyList<AmendmentCandidateV1>()
        var explanations=emptyList<ScopedDescriptiveItem>()
        var issues=emptyList<AmendmentPolicyIssue>()
        val draft=try { AmendmentSelectionContractV1.decodeDraft(raw,packet) }
            catch(_: IllegalArgumentException) { null }
            catch(_: SerializationException) { null }
        if(draft==null) issues=listOf(AmendmentPolicyIssue(null,null,"rejected","invalid_whole_draft"))
        else {
            val byId=packet.candidates.associateBy { it.candidateId }
            val resolved=draft.candidateIds.map { byId.getValue(it) }.sortedBy { it.sessionId }
            issues=PlanAmendmentPolicy.checkWholeResult(capture.policy.input,now,resolved)
            if(issues.isEmpty()) {
                outcome=if(draft.outcome=="select") "selected" else draft.outcome
                selected=resolved;explanations=draft.explanations
            }
        }
        val policy=requireNotNull(capture.policy.input.plan?.definition?.policy)
        val expires=(listOf(deadline,capture.policy.at.plusSeconds(86400))+selected.flatMap {
            listOf(amendmentStartCutoff(it.before,policy),amendmentStartCutoff(it.after,policy))
        }).min()
        val result=CheckedAmendmentResult(outcome,prepared,selected,explanations,issues,now.toString(),expires.toString(),"")
        val checked=result.copy(resultSha256=AnalysisClaims.hash(json.encodeToString(result)))
        if(json.encodeToString(checked).toByteArray(Charsets.UTF_8).size>MAX_SCOPED_INSPECTION_BYTES) throw ScopedEvidenceInspectionLimit()
        // Even a rejected draft cannot return private source content after a concurrent deletion.
        store.finishAmendmentSelection(capture,minOf(deadline,expires),clock)
        return checked
    }

    companion object {
        private val json=ScopedEvidencePreparation.json
        fun prepare(captured: CapturedAmendmentSelection): PreparedAmendmentSelection {
            val capture=captured.copy(policy=captured.policy.copy(input=json.decodeFromString(json.encodeToString(captured.policy.input))))
            val source=capture.source
            val stored=requireNotNull(source.snapshot)
            val snapshot=stored.snapshot
            val root=requireNotNull(source.scoped?.adaptationReference)
            if(snapshot.state!="PREPARED" || snapshot.packet?.adaptation==null ||
                snapshot.snapshotId!=root.stored.snapshot.snapshotId || snapshot.manifest.blockedReasons.isNotEmpty()) throw ScopedEvidenceConflict()
            val sourceDigest=source.currentDigest()
            if(sourceDigest==null || sourceDigest!=stored.bindingSha256) throw ScopedEvidenceConflict()
            val evidence=requireNotNull(snapshot.packet)
            val facts=requireNotNull(evidence.adaptation)
            val input=capture.policy.input
            val plan=input.plan ?: throw PlanConflict()
            val policy=plan.definition.policy ?: throw PlanConflict()
            val report=PlanAmendmentPolicy.enumerate(input,capture.policy.at)
            if(evidence.plan.plan!=plan.reference || evidence.plan.selection!=input.selection || evidence.plan.policy!=policy ||
                facts.family.plan!=plan.reference || facts.family.timeZone!=policy.adaptationTimeZone ||
                facts.horizon!=report.horizon || facts.family.horizonDays!=policy.horizonDays ||
                evidence.plan.sessions.any { session -> plan.definition.sessions.singleOrNull { it.sessionId==session.sessionId }!=session }) throw PlanConflict()
            val deadline=minOf(root.stored.validUntil,Instant.parse(report.validUntilUtc),capture.policy.at.plusSeconds(86400))
            if(capture.policy.at>=deadline) throw PlanConflict()
            val dependency=AnalysisClaims.hash(json.encodeToString(listOf(snapshot.snapshotId,requireNotNull(snapshot.packetSha256),sourceDigest,
                facts.workKey,report.dependencySha256,report.policyVersion,json.encodeToString(report.horizon),deadline.toString())))
            val revisions=(snapshot.manifest.dependencies.map { AmendmentRevisionBinding(it.kind,it.identity,it.revision.toLong()) }+
                input.contexts.map { AmendmentRevisionBinding("context",it.context.contextId,it.context.revision.toLong()) }+
                input.commitments.map { AmendmentRevisionBinding("commitment",it.reference.commitmentId,it.reference.revision.toLong()) }+
                input.sourceHeads.map { (id,revision) -> AmendmentRevisionBinding("intake",id,revision.toLong()) }+
                root.stored.work.policies.map { AmendmentRevisionBinding(it.kind,it.ruleId,it.revision.toLong()) }+
                root.stored.work.reasons.map { AmendmentRevisionBinding("adaptation_reason",it.reasonId,it.revision) }+
                AmendmentRevisionBinding("plan_selection",plan.reference.planId,input.selection.generation))
                .distinct().sortedWith(compareBy({it.kind},{it.identity},{it.revision}))
            var packet:AmendmentSelectionPacketV1?=null
            var invocation:ConnectedReviewInvocation?=null
            if(report.candidates.isNotEmpty()) {
                // Candidates outside the immutable packet must not be silently dropped or added.
                if(report.candidates.any { it.before !in evidence.plan.sessions }) throw ScopedEvidenceConflict()
                packet=AmendmentSelectionContractV1.packet(snapshot.snapshotId,dependency,ScopedDescriptiveContractV1.packet(evidence),report.candidates)
                invocation=ConnectedReviewInvocation.amendment(packet)
            }
            val inspection=AmendmentSelectionInspection(report.status,snapshot.snapshotId,requireNotNull(snapshot.packetSha256),sourceDigest,
                facts.workKey,report,revisions,packet,invocation?.exactPrompt,invocation?.exactCorrectionPrompt,invocation?.consentDigest,deadline.toString())
            val encoded=json.encodeToString(inspection)
            if(encoded.toByteArray(Charsets.UTF_8).size>MAX_SCOPED_INSPECTION_BYTES) throw ScopedEvidenceInspectionLimit()
            return PreparedAmendmentSelection(capture,encoded,deadline)
        }
    }
    override fun toString()="PreparedAmendmentSelection([REDACTED])"
}

/** An inert local API for exact packet inspection and submitted-draft checking, not a runtime. */
internal class PlanAmendmentPreparation(private val store: HistoryStore,private val clock: Clock=Clock.systemUTC()) {
    fun prepare(snapshotId: String): PreparedAmendmentSelection =
        PreparedAmendmentSelection.prepare(store.captureAmendmentSelection(snapshotId,clock.instant())).also { it.finish(store,clock) }

    fun validate(prepared: PreparedAmendmentSelection,raw: String): CheckedAmendmentResult=prepared.validate(store,clock,raw)

    fun validate(request: AmendmentValidationRequest): CheckedAmendmentResult {
        require(request.expectedPromptBindingSha256.matches(Regex("[a-f0-9]{64}")))
        require(request.rawDraft.toByteArray(Charsets.UTF_8).size<=12*1024)
        val prepared=prepare(request.snapshotId)
        if(prepared.inspection().exactPromptBindingSha256!=request.expectedPromptBindingSha256) throw PlanConflict()
        return validate(prepared,request.rawDraft)
    }
}
