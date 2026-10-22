package com.gtrainer

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Compact immutable local projection. Raw drafts, prompts and duplicated evidence are not retained. */
@Serializable
data class PlanAmendmentProposal(val proposalId: String,val sourceSnapshotId: String,val basePlan: PlanRef,
    val selectionGeneration: Long,val horizon: ScopedDateRange,val policyVersion: String,val contractVersion: String,
    val sourcePacketSha256: String,val sourceBindingSha256: String,val adaptationWorkKey: String,
    val policyInputSha256: String,val candidateSetSha256: String,val exactPromptBindingSha256: String,
    val revisions: List<AmendmentRevisionBinding>,val sourceRevision: Long,val removalGeneration: Long,
    val constraintSequence: Long,val constraintDeletionGeneration: Long,
    val outcome: String,val selected: List<AmendmentCandidateV1>,val explanations: List<ScopedDescriptiveItem>,
    val issues: List<AmendmentPolicyIssue>,val createdUtc: String,val checkedUtc: String,val expiresUtc: String,
    val version: String="local_amendment_proposal_v1",
    val limitation: String="boundary_only_not_exhaustive;mechanical_not_clinical;explicit_approval_required;plan_unchanged") {
    override fun toString()="PlanAmendmentProposal([REDACTED])"
}

@Serializable
data class PlanAmendmentProposalInspection(val proposalId: String,val proposalSha256: String,val disposition: String,
    val reason: String?,val freshness: String,val state: String,val createdUtc: String,val expiresUtc: String,
    val proposal: PlanAmendmentProposal?,val rejectedUtc: String?,val approval: String="unavailable_terminal_or_noncurrent",
    val appliedReceipt: PlanAmendmentApprovalReceipt?=null,val reviewConstraints: AmendmentReviewConstraints?=null) {
    override fun toString()="PlanAmendmentProposalInspection([REDACTED])"
}

@Serializable
data class AmendmentReviewOccupancy(val sessionId: String,val schedule: PlanSchedule,val omitted: Boolean)

/** Ephemeral complete local input superset, not a second applicability evaluator or provider packet. */
@Serializable
data class AmendmentReviewConstraints(val proposalId: String,val proposalSha256: String,val policyInputSha256: String,
    val basePlan: PlanRef,val selectionGeneration: Long,val policyVersion: String,val asOf: String,val policy: PlanPolicy,
    val horizon: ScopedDateRange,val contexts: List<ContextLifecycleView>,val commitments: List<ConfirmedCommitment>,
    val occupancy: List<AmendmentReviewOccupancy>,
    val coverage: String="complete_captured_inputs_not_all_applicable;external_calendar_coverage_unavailable",
    val limitation: String="mechanical_not_clinical;inspection_not_inference_or_approval") {
    override fun toString()="AmendmentReviewConstraints([REDACTED])"
}

@Serializable
data class PlanAmendmentProposalRejection(val expectedProposalSha256: String)

@Serializable
data class PlanAmendmentProposalIndexEntry(val proposalId: String,val disposition: String,val createdUtc: String,val expiresUtc: String)

@Serializable
data class PlanAmendmentProposalIndex(val offset: Int,val limit: Int,val total: Int,val entries: List<PlanAmendmentProposalIndexEntry>,
    val nextOffset: Int?,val freshness: String="uninspected_open_exact_record_for_current_status")

internal data class CapturedProposalInspection(val stored: StoredPlanAmendmentProposal,val capture: CapturedAmendmentSelection) {
    override fun toString()="CapturedProposalInspection([REDACTED])"
}

/** Non-serializable owner capability retaining the original validation capture until the save transaction. */
internal class PreparedProposalSave private constructor(val capture: CapturedAmendmentSelection,val row: StoredPlanAmendmentProposal) {
    companion object {
        fun fromDraft(preparation: PreparedAmendmentSelection,store: HistoryStore,clock: Clock,raw: String,submission: String): PreparedProposalSave {
            val checked=preparation.validate(store,clock,raw)
            val capture=preparation.proposalCapture()
            val prep=checked.preparation;val root=requireNotNull(capture.source.scoped?.adaptationReference).current.root
            val policy=prep.policy
            val id=UUID.randomUUID().toString()
            val proposal=PlanAmendmentProposal(id,prep.sourceSnapshotId,requireNotNull(policy.basePlan),policy.selectionGeneration,
                requireNotNull(policy.horizon),policy.policyVersion,AmendmentSelectionContractV1.PROFILE,
                prep.sourcePacketSha256,prep.sourceBindingSha256,prep.adaptationWorkKey,policy.dependencySha256,
                requireNotNull(prep.packet).candidateSetSha256,requireNotNull(prep.exactPromptBindingSha256),prep.revisions,
                root.sourceRevision,root.removalGeneration,root.constraintFrontier.sequence,root.constraintFrontier.deletionGeneration,
                checked.outcome,checked.selected,checked.explanations,checked.issues,policy.asOf,checked.checkedUtc,checked.expiresUtc)
            val encoded=ScopedEvidencePreparation.json.encodeToString(proposal)
            require(encoded.toByteArray(Charsets.UTF_8).size<=256*1024) { "Proposal projection exceeds its local byte bound" }
            val disposition=when(checked.outcome) {
                "selected" -> "pending"
                "no_change" -> "no_change"
                "needs_clarification" -> "needs_confirmation"
                "rejected" -> "rejected"
                else -> throw IllegalArgumentException("Unknown checked outcome")
            }
            val reason=if(disposition=="rejected") {
                if(checked.issues.any { it.reason=="invalid_whole_draft" }) "invalid_whole_draft" else "policy_validation"
            } else null
            return PreparedProposalSave(capture,StoredPlanAmendmentProposal(id,prep.sourceSnapshotId,submission,
                AnalysisClaims.hash(encoded),encoded,proposal.createdUtc,proposal.expiresUtc,disposition,reason,
                if(disposition=="rejected") checked.checkedUtc else null))
        }
    }
    override fun toString()="PreparedProposalSave([REDACTED])"
}

internal object ProposalFreshness {
    fun current(captured: CapturedProposalInspection): Boolean = try {
        val row=captured.stored;val capture=captured.capture
        val proposal=ScopedEvidencePreparation.json.decodeFromString<PlanAmendmentProposal>(row.projectionJson)
        val now=capture.policy.at
        if(now<Instant.parse(row.createdUtc) || now>=Instant.parse(row.expiresUtc)) false
        else {
            val source=capture.source;val root=source.scoped?.adaptationReference?.current?.root
            root!=null && proposal.version=="local_amendment_proposal_v1" && proposal.policyVersion==PlanAmendmentPolicy.VERSION &&
                proposal.contractVersion==AmendmentSelectionContractV1.PROFILE && source.snapshot?.snapshot?.state=="PREPARED" &&
                source.snapshot.snapshot.packetSha256==proposal.sourcePacketSha256 && source.currentDigest()==proposal.sourceBindingSha256 &&
                source.snapshot.snapshot.packet?.adaptation?.workKey==proposal.adaptationWorkKey &&
                capture.policy.input.plan?.reference==proposal.basePlan && capture.policy.input.selection.generation==proposal.selectionGeneration &&
                PlanAmendmentPolicy.binding(capture.policy.input)==proposal.policyInputSha256 &&
                root.sourceRevision==proposal.sourceRevision && root.removalGeneration==proposal.removalGeneration &&
                root.constraintFrontier.sequence==proposal.constraintSequence && root.constraintFrontier.deletionGeneration==proposal.constraintDeletionGeneration
        }
    } catch(_: IllegalArgumentException) { false }
      catch(_: ScopedEvidenceConflict) { false }
      catch(_: TrendSizeLimit) { false }
      catch(_: CommitmentProjectionLimit) { false }
      catch(_: CommitmentTimeUncertain) { false }

    fun inspection(row: StoredPlanAmendmentProposal,current: Boolean,at: Instant): PlanAmendmentProposalInspection {
        val freshness=when {
            at>=Instant.parse(row.expiresUtc) -> "expired"
            at<Instant.parse(row.createdUtc) || !current -> "stale"
            else -> "current"
        }
        return PlanAmendmentProposalInspection(row.proposalId,row.projectionSha256,row.state,row.reason,freshness,
            if(freshness=="current") row.state else freshness,row.createdUtc,row.expiresUtc,
            if(freshness=="current") ScopedEvidencePreparation.json.decodeFromString<PlanAmendmentProposal>(row.projectionJson) else null,row.rejectedUtc,
            if(freshness=="current" && row.state=="pending") "explicit_exact_proposal_approval_required" else "unavailable_terminal_or_noncurrent")
    }
}

/** Explicit local generation/rejection are writes; inspection never advances a state or starts work. */
internal class PlanAmendmentProposalService(private val store: HistoryStore,private val clock: Clock) {
    fun generate(request: AmendmentValidationRequest): PlanAmendmentProposalInspection {
        require(request.rawDraft.toByteArray(Charsets.UTF_8).size<=12*1024)
        validateContextId(request.snapshotId)
        require(request.expectedPromptBindingSha256.matches(Regex("[a-f0-9]{64}")))
        val submission=AnalysisClaims.hash(ScopedEvidencePreparation.json.encodeToString(listOf(
            "local_proposal_submission_v1",request.snapshotId,request.expectedPromptBindingSha256,request.rawDraft)))
        store.proposalForSubmission(submission)?.let { return inspect(it) }
        val preparation=PlanAmendmentPreparation(store,clock).prepare(request.snapshotId)
        if(preparation.inspection().exactPromptBindingSha256!=request.expectedPromptBindingSha256) throw PlanConflict()
        val prepared=preparation.prepareProposal(store,clock,request.rawDraft,submission)
        val row=store.savePlanAmendmentProposal(prepared,clock)
        return inspect(row.proposalId)
    }

    fun inspect(id: String): PlanAmendmentProposalInspection {
        store.appliedProposalInspection(id)?.let { return it }
        val capture=store.captureProposalInspection(id,clock.instant())
        val current=ProposalFreshness.current(capture)
        return store.finishProposalInspection(capture,current,clock)
    }

    fun reject(id: String,request: PlanAmendmentProposalRejection): PlanAmendmentProposalInspection {
        store.rejectPlanAmendmentProposal(id,request.expectedProposalSha256,clock.instant())
        return inspect(id)
    }
}
