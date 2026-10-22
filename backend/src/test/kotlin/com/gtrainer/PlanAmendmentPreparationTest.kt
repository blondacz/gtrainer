package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.time.*
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlin.test.*

class PlanAmendmentPreparationTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private var now=at
    private val clock=object:Clock() {
        override fun instant()=now
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=this
    }
    private val json=ScopedEvidencePreparation.json
    private fun fixture(block:(Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("amendment-preparation-");val path=dir.resolve("synthetic.db");now=at
        try { HistoryStore(path,clock).use { block(path,it) } }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
    private fun session(day:String="2026-10-18",move:Boolean=false):PlannedSession {
        val author=PlanAuthor("user","PRIVATE_AUTHOR_MARKER")
        return PlannedSession(UUID.randomUUID().toString(),author,"run","PRIVATE_OBJECTIVE_MARKER",
            PlanSchedule("timed",startsLocal="${day}T09:00",endsLocal="${day}T10:00",timeZone="UTC"),
            listOf(PlannedTarget("duration","seconds","moving",1800.0,2400.0)),
            permissions=if(move) AmendmentPermissions(PlanMoveWindow("2026-10-21T09:00","2026-10-21T09:00")) else AmendmentPermissions(omit=true))
    }
    private fun setup(store:HistoryStore,sessions:List<PlannedSession> = listOf(session()),days:Int=7):AuthoredPlan {
        val plan=store.createPlan(AuthoredPlanRequest("Synthetic",PlanAuthor("user","synthetic"),sessions,PlanPolicy("UTC",days,0)),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference))
        store.updateReviewSchedule(0,ReviewScheduleConfiguration(timeZone="UTC"),at,null,null)
        return plan
    }
    private fun snapshot(store:HistoryStore)=store.publishAdaptationResult(PreparedAdaptationResult.prepare(store.captureAdaptationResult(null,now)),now)
    private fun prepare(store:HistoryStore,id:String)=PlanAmendmentPreparation(store,clock).prepare(id)
    private fun draft(inspection:AmendmentSelectionInspection,selected:List<AmendmentCandidateV1> = inspection.packet!!.candidates.take(1),outcome:String="select"):String {
        val packet=inspection.packet!!
        val explanations=if(outcome=="select") selected.map { candidate -> packet.explanationChoices.single { it.text.startsWith("Candidate ${candidate.candidateId}:") } }
            else listOf(packet.explanationChoices.single { it.text.startsWith(if(outcome=="no_change") "No amendment candidate" else "Clarification is requested") })
        return AmendmentSelectionContractV1.encodeDraft(AmendmentSelectionDraftV1(AmendmentSelectionContractV1.PROFILE,packet.candidateSetSha256,outcome,if(outcome=="select") selected.map { it.candidateId } else emptyList(),explanations))
    }
    private fun count(path:Path,table:String)=java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $table").use { r -> r.next();r.getInt(1) } }
    }

    @Test fun actualSnapshotBecomesBoundCheckedResultWithNoWritesOrExecutionGenerationChanges()=fixture { path,store ->
        val plan=setup(store);val source=snapshot(store);val before=store.capturePlanAmendment(at)
        val queue=store.reviewScheduleState();val plans=store.plans()
        val prepared=prepare(store,source.snapshotId);val inspection=prepared.inspection()
        assertEquals("available",inspection.status);assertEquals(source.packetSha256,inspection.sourcePacketSha256)
        assertNotNull(inspection.exactPromptBindingSha256);assertNotNull(inspection.exactCorrectionPrompt)
        assertTrue(inspection.revisions.any { it.kind=="plan" && it.identity==plan.reference.planId && it.revision==1L })
        val result=PlanAmendmentPreparation(store,clock).validate(prepared,draft(inspection))
        assertEquals("selected",result.outcome);assertEquals(inspection.packet!!.candidates.single(),result.selected.single())
        assertEquals("not_approvable_no_persisted_proposal_no_plan_change",result.approval)
        assertEquals(AnalysisClaims.hash(json.encodeToString(result.copy(resultSha256=""))),result.resultSha256)
        assertEquals(plans,store.plans());assertEquals(queue,store.reviewScheduleState())
        assertEquals(before.totalChanges,store.capturePlanAmendment(at).totalChanges)
        assertEquals(0,count(path,"scoped_execution_bindings"));assertEquals(0,count(path,"scoped_execution_state"))
        assertEquals(1,count(path,"scoped_evidence_snapshots"));assertEquals(1,count(path,"plan_revisions"))
        assertTrue(result.explanations.none { it.text.contains("PRIVATE_AUTHOR_MARKER") || it.text.contains("PRIVATE_OBJECTIVE_MARKER") })
        HistoryStore(path,clock).use { reopened -> assertEquals(inspection,prepare(reopened,source.snapshotId).inspection()) }
    }

    @Test fun everyChangedOutputClaimAndPrimitiveShapeRejectsWholeDraftNotJustInvalidItem()=fixture { _,store ->
        setup(store);val prepared=prepare(store,snapshot(store).snapshotId);val valid=draft(prepared.inspection())
        val bad=listOf(valid.replace("1800.0","9999.0"),valid.replace("seconds","minutes"),valid.replace("2026-10-18","2026-10-19"),
            valid.replace("not verified coach/clinician guidance","clinician verified"),valid.replace("authored plan","medically suitable"),
            valid.replace("\"high\"","\"low\""),valid.replace("\"revision\":1","\"revision\":\"1\""),
            valid.replaceFirst("\"outcome\":","\"outcome\":\"select\",\"outcome\":"),valid.dropLast(1)+",\"operations\":[]}")
        for(raw in bad) {
            assertNotEquals(valid,raw)
            val result=PlanAmendmentPreparation(store,clock).validate(prepared,raw)
            assertEquals("rejected",result.outcome);assertTrue(result.selected.isEmpty());assertTrue(result.explanations.isEmpty())
            assertEquals("invalid_whole_draft",result.issues.single().reason)
        }
    }

    @Test fun twoValidCandidatesAtSameNewDestinationRejectEntireCheckedResult()=fixture { _,store ->
        setup(store,listOf(session(move=true),session("2026-10-19",true)))
        val prepared=prepare(store,snapshot(store).snapshotId);val inspection=prepared.inspection()
        assertEquals(2,inspection.packet!!.candidates.size)
        val result=PlanAmendmentPreparation(store,clock).validate(prepared,draft(inspection,inspection.packet.candidates))
        assertEquals("rejected",result.outcome);assertTrue(result.selected.isEmpty());assertTrue(result.explanations.isEmpty())
        assertTrue(result.issues.any { it.reason=="combined_plan_session_overlap" })
    }

    @Test fun clippedEvidenceRetainsCompletePlanLocallyWithoutExpandingImmutablePacket()=fixture { _,store ->
        val outside=session("2026-11-10").copy(permissions=AmendmentPermissions())
        val plan=setup(store,listOf(session(),outside));val source=snapshot(store)
        assertEquals(1,source.packet!!.plan.sessions.size);assertEquals(2,plan.definition.sessions.size)
        val prepared=prepare(store,source.snapshotId)
        assertEquals(source.packet,prepared.inspection().packet!!.evidence.evidence)
        assertEquals("selected",PlanAmendmentPreparation(store,clock).validate(prepared,draft(prepared.inspection())).outcome)
    }

    @Test fun genericOutcomesAreDistinctAndNeverChangePlan()=fixture { _,store ->
        setup(store);val prepared=prepare(store,snapshot(store).snapshotId);val plans=store.plans()
        for(outcome in listOf("no_change","needs_clarification")) {
            val result=PlanAmendmentPreparation(store,clock).validate(prepared,draft(prepared.inspection(),outcome=outcome))
            assertEquals(outcome,result.outcome);assertTrue(result.selected.isEmpty());assertEquals(1,result.explanations.size)
        }
        assertEquals(plans,store.plans())
    }

    @Test fun emptyCandidatesStayFactualAndNeverBecomeInferredNoChange()=fixture { _,store ->
        setup(store,listOf(session().copy(permissions=AmendmentPermissions())))
        val prepared=prepare(store,snapshot(store).snapshotId);val inspection=prepared.inspection()
        assertEquals("no_candidates",inspection.status);assertNull(inspection.packet);assertNull(inspection.exactPrompt)
        assertFailsWith<IllegalArgumentException> { PlanAmendmentPreparation(store,clock).validate(prepared,"{}") }
    }

    @Test fun ownerSourcePlanSelectionContextCommitmentIntakeAndRuleMutationsFenceHandles() {
        for(kind in listOf("plan","selection","context","commitment","intake","signal","workload","source")) fixture { _,store ->
            val plan=setup(store)
            val sourceIntake=store.createIntake(ContextIntakeRequest("synthetic","user_report","synthetic","2026-10-01"),at)
            val prepared=prepare(store,snapshot(store).snapshotId);val raw=draft(prepared.inspection())
            when(kind) {
                "plan" -> store.correctPlan(plan.reference.planId,PlanCorrection(1,1,plan.definition.copy(title="changed")),at)
                "selection" -> { store.selectPlan(PlanSelectionRequest(1,null));store.selectPlan(PlanSelectionRequest(2,plan.reference)) }
                "context" -> store.createContext(AthleteContextRequest("restriction","user_report","synthetic","2026-10-01",content="synthetic",restrictionKind="blocked_sport",restrictionValue="run"))
                "commitment" -> store.confirmCommitment(CommitmentConfirmation(sourceIntake.intakeId,1,CommitmentDefinition("busy","commitment","UTC","2026-10-18T09:00","2026-10-18T10:00")),at)
                "intake" -> store.correctIntake(sourceIntake.intakeId,ContextIntakeCorrection(1,ContextIntakeRequest("changed","user_report","synthetic","2026-10-01")),at)
                "signal" -> store.saveSignalRule(PersonalSignalRuleRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,false,null),at)
                "workload" -> store.saveWorkloadRule(ExtraWorkloadRuleRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,false,null),at)
                "source" -> store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(syntheticActivity())),at)
            }
            assertFailsWith<PlanConflict>(kind) { PlanAmendmentPreparation(store,clock).validate(prepared,raw) }
        }
    }

    @Test fun expiredBackwardCrossOwnerAndExternalMutationRejectWithoutReturningSourceContent()=fixture { path,store ->
        setup(store);val prepared=prepare(store,snapshot(store).snapshotId);val inspection=prepared.inspection();val raw=draft(inspection)
        now=at.minusNanos(1);assertFailsWith<PlanConflict> { PlanAmendmentPreparation(store,clock).validate(prepared,raw) }
        now=Instant.parse(inspection.validUntilUtc);assertFailsWith<PlanConflict> { PlanAmendmentPreparation(store,clock).validate(prepared,raw) }
        now=at
        HistoryStore(path,clock).use { other ->
            assertFailsWith<PlanConflict> { PlanAmendmentPreparation(other,clock).validate(prepared,raw) }
            other.createIntake(ContextIntakeRequest("unrelated","user_report","synthetic","2026-10-01"),at)
        }
        assertFailsWith<PlanConflict> { PlanAmendmentPreparation(store,clock).validate(prepared,raw) }
    }

    @Test fun statelessValidationReconstructsOnlyExactCurrentPromptAndRejectsSubstitution()=fixture { _,store ->
        setup(store);val id=snapshot(store).snapshotId;val inspection=prepare(store,id).inspection();val service=PlanAmendmentPreparation(store,clock)
        val request=AmendmentValidationRequest(id,inspection.exactPromptBindingSha256!!,draft(inspection))
        assertEquals("selected",service.validate(request).outcome)
        assertFailsWith<PlanConflict> { service.validate(request.copy(expectedPromptBindingSha256="e".repeat(64))) }
        store.createIntake(ContextIntakeRequest("changed inventory","user_report","synthetic","2026-10-01"),at)
        assertFails { service.validate(request) }
    }

    @Test fun nonAdaptationSnapshotAndChangedRealSnapshotCannotBePromotedToSelection()=fixture { _,store ->
        setup(store)
        val request=ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="day",wellnessPolicy="off"),purpose="adaptation")
        val capture=store.captureScopedEvidence(request,at)
        val report=ScopedTrends.report(capture.read.scope,capture.read.history,capture.read.statuses)
        val ordinary=store.saveScopedEvidence(prepareScopedEvidence(request,capture,report),at)
        assertFails { prepare(store,ordinary.snapshotId) }
        val real=snapshot(store)
        store.createContext(AthleteContextRequest("restriction","user_report","synthetic","2026-10-01",content="synthetic",restrictionKind="blocked_sport",restrictionValue="run"))
        assertFails { prepare(store,real.snapshotId) }
    }

    @Test fun contextDeletionFencesEvenRejectedOutputAndPreparationFinishRaces()=fixture { _,store ->
        setup(store)
        val context=store.createContext(AthleteContextRequest("note","user_report","synthetic","2026-10-01",content="PRIVATE_CONTEXT_MARKER"))
        val id=snapshot(store).snapshotId
        val prepared=prepare(store,id)
        val captured=store.captureAmendmentSelection(id,at)
        val staged=PreparedAmendmentSelection.prepare(captured)
        assertTrue(store.deleteContext(context.contextId))
        assertFailsWith<PlanConflict> { staged.finish(store,clock) }
        assertFailsWith<PlanConflict> { PlanAmendmentPreparation(store,clock).validate(prepared,"{}") }
    }
}
