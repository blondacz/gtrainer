package com.gtrainer

import java.time.Clock
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class PlanAmendmentApprovalRequest(val operationId: String,val expectedBasePlan: PlanRef,
    val expectedProposalSha256: String,val expectedSelectionGeneration: Long) {
    init {
        validateContextId(operationId)
        require(expectedProposalSha256.matches(Regex("[a-f0-9]{64}")) && expectedSelectionGeneration>=0)
    }
    override fun toString()="PlanAmendmentApprovalRequest([REDACTED])"
}

@Serializable
data class PlanAmendmentApprovalReceipt(val operationId: String,val proposalId: String,val appliedPlan: PlanRef,
    val appliedUtc: String,val recordedBy: String="authenticated_user",
    val scope: String="exact_local_amendment_only_not_inference_hosted_transfer_or_external_write") {
    override fun toString()="PlanAmendmentApprovalReceipt([REDACTED])"
}

internal fun approvalRequestDigest(proposalId: String,request: PlanAmendmentApprovalRequest): String {
    validateContextId(proposalId)
    return AnalysisClaims.hash("exact_local_amendment_approval_v1\n"+proposalId+"\n"+
        ScopedEvidencePreparation.json.encodeToString(request))
}

internal fun StoredPlanAmendmentApproval.receipt()=PlanAmendmentApprovalReceipt(operationId,proposalId,appliedPlan,appliedUtc)

/** Only a frozen current owner capture can create this in-process approval capability. */
internal class PreparedProposalApproval private constructor(val captured: CapturedProposalInspection,
    val request: PlanAmendmentApprovalRequest,val requestSha256: String,private val projectionJson: String) {
    fun proposal(): PlanAmendmentProposal=ScopedEvidencePreparation.json.decodeFromString(projectionJson)

    companion object {
        fun prepare(capture: CapturedProposalInspection,request: PlanAmendmentApprovalRequest): PreparedProposalApproval {
            val row=capture.stored
            val json=ScopedEvidencePreparation.json
            val frozen=capture.copy(capture=capture.capture.copy(policy=capture.capture.policy.copy(
                input=json.decodeFromString(json.encodeToString(capture.capture.policy.input)))))
            val proposal=json.decodeFromString<PlanAmendmentProposal>(row.projectionJson)
            if(row.state!="pending" || proposal.outcome!="selected" || proposal.selected.isEmpty() ||
                row.projectionSha256!=request.expectedProposalSha256 || proposal.basePlan!=request.expectedBasePlan ||
                proposal.selectionGeneration!=request.expectedSelectionGeneration || proposal.proposalId!=row.proposalId ||
                proposal.sourceSnapshotId!=row.snapshotId || proposal.createdUtc!=row.createdUtc || proposal.expiresUtc!=row.expiresUtc ||
                !ProposalFreshness.current(frozen)) throw PlanConflict()
            val input=frozen.capture.policy.input;val at=frozen.capture.policy.at
            if(PlanAmendmentPolicy.checkWholeResult(input,at,proposal.selected).isNotEmpty()) throw PlanConflict()
            return PreparedProposalApproval(frozen,request,approvalRequestDigest(row.proposalId,request),row.projectionJson)
        }
    }
    override fun toString()="PreparedProposalApproval([REDACTED])"
}

internal class PlanAmendmentApprovalService(private val store: HistoryStore,private val clock: Clock) {
    fun approve(id: String,request: PlanAmendmentApprovalRequest): PlanAmendmentApprovalReceipt {
        val digest=approvalRequestDigest(id,request)
        store.amendmentApprovalReplay(id,request.operationId,digest)?.let { return it.receipt() }
        val capture=store.captureProposalInspection(id,clock.instant())
        val prepared=PreparedProposalApproval.prepare(capture,request)
        return store.applyPlanAmendment(prepared,clock).receipt()
    }
}
