package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.*
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlin.test.*

class PlanAmendmentApprovalOwnerTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private var now=at
    private val clock=object:Clock() {
        override fun instant()=now
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=this
    }
    private val json=ScopedEvidencePreparation.json
    private fun fixture(block:(Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("approval-owner-");val path=dir.resolve("synthetic.db");now=at
        try { HistoryStore(path,clock).use { block(path,it) } }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
    private fun session(day:String,permissions:AmendmentPermissions):PlannedSession = PlannedSession(UUID.randomUUID().toString(),
        PlanAuthor("coach","UNVERIFIED_SYNTHETIC_AUTHOR"),"run","SYNTHETIC_OBJECTIVE",PlanSchedule("timed",
            startsLocal="${day}T09:00",endsLocal="${day}T10:00",timeZone="UTC"),
        listOf(PlannedTarget("duration","seconds","moving",1800.0,2400.0)),permissions=permissions)
    private fun setup(store:HistoryStore,sessions:List<PlannedSession> = listOf(
        session("2026-10-18",AmendmentPermissions(move=PlanMoveWindow("2026-10-20T09:00","2026-10-20T09:00"))),
        session("2026-10-19",AmendmentPermissions(reduceDuration=DurationReductionBounds("moving",900,2400))),
        session("2026-10-21",AmendmentPermissions(omit=true)),session("2026-10-22",AmendmentPermissions()))):AuthoredPlan {
        val plan=store.createPlan(AuthoredPlanRequest("Synthetic authored plan",PlanAuthor("coach","UNVERIFIED_SYNTHETIC_AUTHOR"),sessions,PlanPolicy("UTC",10,0)),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference));store.updateReviewSchedule(0,ReviewScheduleConfiguration(timeZone="UTC"),at,null,null)
        return plan
    }
    private fun generate(store:HistoryStore,outcome:String="select",all:Boolean=true):Pair<PlanAmendmentProposalInspection,AmendmentValidationRequest> {
        val id=store.publishAdaptationResult(PreparedAdaptationResult.prepare(store.captureAdaptationResult(null,now)),now).snapshotId
        val inspection=PlanAmendmentPreparation(store,clock).prepare(id).inspection();val packet=inspection.packet!!
        val selected=packet.candidates.groupBy { it.sessionId }.values.map { it.first() }.let { if(all) it else it.take(1) }
        val choices=if(outcome=="select") selected.map { c -> packet.explanationChoices.single { it.text.startsWith("Candidate ${c.candidateId}:") } }
            else listOf(packet.explanationChoices.single { it.text.startsWith(if(outcome=="no_change") "No amendment candidate" else "Clarification is requested") })
        val draft=AmendmentSelectionDraftV1(AmendmentSelectionContractV1.PROFILE,packet.candidateSetSha256,if(outcome=="rejected") "no_change" else outcome,
            if(outcome=="select") selected.map { it.candidateId } else emptyList(),choices)
        val input=AmendmentValidationRequest(id,inspection.exactPromptBindingSha256!!,if(outcome=="rejected") "{bad" else AmendmentSelectionContractV1.encodeDraft(draft))
        return PlanAmendmentProposalService(store,clock).generate(input) to input
    }
    private fun request(proposal:PlanAmendmentProposalInspection,operation:String=UUID.randomUUID().toString())=PlanAmendmentApprovalRequest(
        operation,proposal.proposal!!.basePlan,proposal.proposalSha256,proposal.proposal.selectionGeneration)
    private fun approve(store:HistoryStore,proposal:PlanAmendmentProposalInspection,request:PlanAmendmentApprovalRequest=request(proposal))=
        PlanAmendmentApprovalService(store,clock).approve(proposal.proposalId,request)
    private fun count(path:Path,table:String)=DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
        db.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $table").use { it.next();it.getInt(1) } }
    }
    private fun context()=AthleteContextRequest("note","user_report","synthetic","2026-10-01",content="PRIVATE_APPROVAL_CONTEXT_MARKER")

    @Test fun exactMixedDiffAppendsOneLocalRevisionPreservingSessionOrderDetailsAndUnverifiedAttribution()=fixture { path,store ->
        val base=setup(store);val (proposal,submission)=generate(store);val selected=proposal.proposal!!.selected
        assertEquals(setOf("move_session","reduce_duration","omit_session"),selected.map { it.operation }.toSet())
        val oldCalendar=PlanService(store,clock).calendar("2026-10-15","2026-10-24","UTC")
        val receipt=approve(store,proposal)
        assertEquals(PlanRef(base.reference.planId,2),receipt.appliedPlan);assertEquals("authenticated_user",receipt.recordedBy)
        val saved=store.plan(receipt.appliedPlan.planId,2)
        val replacements=selected.associate { it.sessionId to it.after }
        assertEquals(base.definition.copy(sessions=base.definition.sessions.map { replacements[it.sessionId] ?: it }),saved.definition)
        assertEquals(base.definition.author,saved.definition.author);assertEquals(1,saved.previousRevision)
        assertEquals(base,store.plan(base.reference.planId,1));assertEquals(PlanSelection(2,saved.reference),store.plans().selection)
        assertNotEquals(oldCalendar,PlanService(store,clock).calendar("2026-10-15","2026-10-24","UTC"))
        assertTrue(saved.definition.sessions.single { it.sessionId==selected.single { it.operation=="omit_session" }.sessionId }.omitted)
        assertEquals(2,count(path,"plan_revisions"));assertEquals(1,count(path,"plan_amendment_approval_audit"));assertEquals(1,count(path,"plan_amendment_approval_bindings"))
        assertEquals(0,count(path,"scoped_execution_bindings"))
        val applied=PlanAmendmentProposalService(store,clock).inspect(proposal.proposalId)
        assertEquals("applied",applied.state);assertEquals("not_applicable",applied.freshness);assertNull(applied.proposal);assertEquals(receipt,applied.appliedReceipt)
        assertEquals(applied,PlanAmendmentProposalService(store,clock).generate(submission))
        assertFailsWith<PlanConflict> { PlanAmendmentProposalService(store,clock).reject(proposal.proposalId,PlanAmendmentProposalRejection(proposal.proposalSha256)) }
    }

    @Test fun exactReplaySurvivesRestartExpiryAndLaterPlanCorrectionsButNewOperationOrPayloadCannotApply()=fixture { path,store ->
        val base=setup(store);val (proposal,_)=generate(store);val request=request(proposal);val receipt=approve(store,proposal,request)
        val saved=store.plan(base.reference.planId,2)
        store.correctPlan(base.reference.planId,PlanCorrection(2,2,saved.definition.copy(title="Later edit")),now)
        now=Instant.parse(proposal.expiresUtc).plusSeconds(10)
        assertEquals(receipt,approve(store,proposal,request));assertEquals(3,count(path,"plan_revisions"))
        HistoryStore(path,clock).use { other -> assertEquals(receipt,approve(other,proposal,request)) }
        assertFailsWith<PlanConflict> { approve(store,proposal,request.copy(expectedProposalSha256="e".repeat(64))) }
        assertFailsWith<PlanConflict> { approve(store,proposal,request.copy(expectedSelectionGeneration=99)) }
        assertFailsWith<PlanConflict> { approve(store,proposal,request.copy(operationId=UUID.randomUUID().toString())) }
        assertEquals("applied",PlanAmendmentProposalService(store,clock).inspect(proposal.proposalId).state)
    }

    @Test fun invalidExpectationNonselectedRejectedAndExpiredProposalsNeverAppendRevision() {
        for(kind in listOf("digest","revision","generation","no_change","needs_clarification","rejected","user_rejected","expiry","backwards")) fixture { path,store ->
            setup(store);val outcome=if(kind in setOf("no_change","needs_clarification","rejected")) kind else "select"
            val (proposal,_)=generate(store,outcome);var request=request(proposal)
            when(kind) {
                "digest" -> request=request.copy(expectedProposalSha256="e".repeat(64))
                "revision" -> request=request.copy(expectedBasePlan=request.expectedBasePlan.copy(revision=2))
                "generation" -> request=request.copy(expectedSelectionGeneration=99)
                "user_rejected" -> PlanAmendmentProposalService(store,clock).reject(proposal.proposalId,PlanAmendmentProposalRejection(proposal.proposalSha256))
                "expiry" -> now=Instant.parse(proposal.expiresUtc)
                "backwards" -> now=at.minusNanos(1)
            }
            assertFailsWith<PlanConflict>(kind) { approve(store,proposal,request) }
            assertEquals(1,count(path,"plan_revisions"));assertEquals(0,count(path,"plan_amendment_approval_audit"))
        }
    }

    @Test fun sourcePlanSelectionAndConstraintChangesCannotBeSilentlyRebased() {
        for(kind in listOf("plan","selection","context","intake","commitment","import","rule","deletion")) fixture { path,store ->
            val base=setup(store);val c=store.createContext(context());val (proposal,_)=generate(store)
            when(kind) {
                "plan" -> store.correctPlan(base.reference.planId,PlanCorrection(1,1,base.definition.copy(title="changed")),now)
                "selection" -> { store.selectPlan(PlanSelectionRequest(1,null));store.selectPlan(PlanSelectionRequest(2,base.reference)) }
                "context" -> store.createContext(context())
                "intake" -> store.createIntake(ContextIntakeRequest("changed","user_report","synthetic","2026-10-01"),now)
                "commitment" -> {
                    val i=store.createIntake(ContextIntakeRequest("busy","user_report","synthetic","2026-10-01"),now)
                    store.confirmCommitment(CommitmentConfirmation(i.intakeId,1,CommitmentDefinition("busy","commitment","UTC","2026-10-20T09:00","2026-10-20T10:00")),now)
                }
                "import" -> store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(syntheticActivity())),now)
                "rule" -> store.saveSignalRule(PersonalSignalRuleRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,false,null),now)
                "deletion" -> store.deleteContext(c.contextId)
            }
            assertFails(kind) { approve(store,proposal) }
            assertEquals(if(kind=="plan")2 else 1,count(path,"plan_revisions"));assertEquals(0,count(path,"plan_amendment_approval_audit"))
        }
    }

    @Test fun originalCaptureRejectsLocalExternalAndStateRacesWhileIdenticalAcceptanceReturnsOneReceipt() {
        for(kind in listOf("local","external","rejection","accepted")) fixture { path,store ->
            setup(store);val (proposal,_)=generate(store);val request=request(proposal)
            val prepared=PreparedProposalApproval.prepare(store.captureProposalInspection(proposal.proposalId,now),request)
            when(kind) {
                "local" -> store.createContext(context())
                "external" -> HistoryStore(path,clock).use { it.createContext(context()) }
                "rejection" -> PlanAmendmentProposalService(store,clock).reject(proposal.proposalId,PlanAmendmentProposalRejection(proposal.proposalSha256))
                "accepted" -> approve(store,proposal,request)
            }
            if(kind=="accepted") assertEquals(2,store.applyPlanAmendment(prepared,clock).appliedPlan.revision)
            else assertFailsWith<PlanConflict>(kind) { store.applyPlanAmendment(prepared,clock) }
            assertEquals(if(kind=="accepted")2 else 1,count(path,"plan_revisions"))
            assertEquals(if(kind=="accepted")1 else 0,count(path,"plan_amendment_approval_audit"))
        }
    }

    @Test fun failureAtStateReceiptBindingOrFinalClockRollsBackEveryWrite() {
        for(kind in listOf("state","audit","binding","expiry","backwards")) fixture { path,store ->
            setup(store);val (proposal,_)=generate(store)
            val prepared=PreparedProposalApproval.prepare(store.captureProposalInspection(proposal.proposalId,now),request(proposal))
            val plans=store.plans();val queue=store.reviewScheduleState()
            if(kind in setOf("state","audit","binding")) DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
                val table=when(kind) { "state" -> "plan_amendment_proposal_state";"audit" -> "plan_amendment_approval_audit";else -> "plan_amendment_approval_bindings" }
                val action=if(kind=="state") "UPDATE" else "INSERT"
                db.createStatement().use { it.execute("CREATE TRIGGER synthetic_failure BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'synthetic'); END") }
            }
            // Trigger installation changes the external token; take a fresh capture for the injected write failure.
            val active=if(kind in setOf("state","audit","binding")) PreparedProposalApproval.prepare(store.captureProposalInspection(proposal.proposalId,now),prepared.request) else prepared
            val applyClock=if(kind in setOf("expiry","backwards")) object:Clock() {
                var calls=0
                override fun instant()=if(calls++==0) at else if(kind=="expiry") Instant.parse(proposal.expiresUtc) else at.minusNanos(1)
                override fun getZone()=ZoneOffset.UTC
                override fun withZone(zone:ZoneId)=this
            } else clock
            assertFails(kind) { store.applyPlanAmendment(active,applyClock) }
            assertEquals(plans,store.plans());assertEquals(queue,store.reviewScheduleState());assertEquals(1,count(path,"plan_revisions"))
            assertEquals(0,count(path,"plan_amendment_approval_audit"));assertEquals(0,count(path,"plan_amendment_approval_bindings"))
            assertEquals("pending",PlanAmendmentProposalService(store,clock).inspect(proposal.proposalId).state)
        }
    }

    @Test fun deletionDisclosesRetainedContentFreeAuditPurgesSensitiveHashesAndPermanentlyBurnsOperationId()=fixture { path,store ->
        setup(store);val context=store.createContext(context());val (proposal,_)=generate(store,all=false);val request=request(proposal)
        val preview=store.previewContextDeletion(context.contextId,now)
        val receipt=approve(store,proposal,request)
        fun delete(p:ContextDeletionPreview)=store.redactAndDeleteContext(context.contextId,ContextDeletionRequest(p.expectedRevision,p.digest,emptyList(),emptyList(),emptyList()),now)
        assertFailsWith<ContextDeletionConflict> { delete(preview) }
        val current=store.previewContextDeletion(context.contextId,now)
        assertEquals(listOf(request.operationId),current.retainedApprovalOperationIds)
        val plans=store.plans();delete(current)
        assertEquals(plans,store.plans());assertEquals(0,count(path,"plan_amendment_proposals"));assertEquals(0,count(path,"plan_amendment_approval_bindings"))
        assertEquals(1,count(path,"plan_amendment_approval_audit"))
        assertFailsWith<PlanNotFound> { approve(store,proposal,request) }
        // Deletion invalidates causal proof; do not bypass that gate to manufacture another result.
        // The burned ID must conflict even before a different proposal is looked up.
        assertFailsWith<PlanConflict> { PlanAmendmentApprovalService(store,clock).approve(UUID.randomUUID().toString(),
            request.copy(expectedBasePlan=plans.selection.selected!!,expectedSelectionGeneration=plans.selection.generation)) }
        assertEquals(receipt.appliedPlan,store.plan(plans.selection.selected!!.planId,receipt.appliedPlan.revision).reference)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { s -> s.executeQuery("PRAGMA table_info(plan_amendment_approval_audit)").use { columns ->
                val names=buildList { while(columns.next()) add(columns.getString("name")) }
                assertEquals(setOf("operation_id","proposal_id","applied_plan_id","applied_revision","applied_utc"),names.toSet())
            } }
        }
    }

    @Test fun concurrentExactApprovalAcrossOwnersCommitsOneRevisionAndRetryReturnsSameReceipt()=fixture { path,store ->
        setup(store);val (proposal,_)=generate(store);val request=request(proposal)
        HistoryStore(path,clock).use { other ->
            val first=PreparedProposalApproval.prepare(store.captureProposalInspection(proposal.proposalId,now),request)
            val second=PreparedProposalApproval.prepare(other.captureProposalInspection(proposal.proposalId,now),request)
            val start=java.util.concurrent.CountDownLatch(1)
            val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val results=listOf(store to first,other to second).map { (owner,prepared) -> pool.submit<Result<StoredPlanAmendmentApproval>> {
                    start.await();runCatching { owner.applyPlanAmendment(prepared,clock) }
                } }
                start.countDown()
                val completed=results.map { it.get(30,java.util.concurrent.TimeUnit.SECONDS) }
                assertTrue(completed.any { it.isSuccess })
                val receipt=approve(store,proposal,request)
                assertEquals(receipt,approve(other,proposal,request))
                completed.mapNotNull { it.getOrNull() }.forEach { assertEquals(receipt,it.receipt()) }
                assertEquals(2,count(path,"plan_revisions"));assertEquals(1,count(path,"plan_amendment_approval_audit"))
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun independentlyRechecksOperationsAndCombinedCollisionsEvenIfStoredProjectionIsCorrupted() {
        for(combined in listOf(false,true)) fixture { path,store ->
            val permission=AmendmentPermissions(move=PlanMoveWindow("2026-10-20T09:00","2026-10-20T09:00"))
            setup(store,listOf(session("2026-10-18",permission),session("2026-10-19",permission)))
            val (original,_)=generate(store,all=false)
            val candidates=PlanAmendmentPolicy.enumerate(store.capturePlanAmendment(now).input,now).candidates
            val operations=if(combined) candidates else listOf(original.proposal!!.selected.single().copy(operation="add_session"))
            val changed=original.proposal!!.copy(selected=operations)
            val encoded=json.encodeToString(changed);val digest=AnalysisClaims.hash(encoded)
            DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
                db.createStatement().use { it.execute("DROP TRIGGER plan_amendment_proposal_immutable") }
                db.prepareStatement("UPDATE plan_amendment_proposals SET projection_json=?,projection_sha256=? WHERE proposal_id=?").use {
                    it.setString(1,encoded);it.setString(2,digest);it.setString(3,original.proposalId);it.executeUpdate()
                }
            }
            assertFailsWith<PlanConflict> { approve(store,original.copy(proposalSha256=digest,proposal=changed)) }
            assertEquals(1,count(path,"plan_revisions"));assertEquals(0,count(path,"plan_amendment_approval_audit"))
        }
    }
}
