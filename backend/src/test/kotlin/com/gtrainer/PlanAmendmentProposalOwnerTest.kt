package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.*
import java.util.UUID
import kotlin.test.*

class PlanAmendmentProposalOwnerTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private var now=at
    private val clock=object:Clock() {
        override fun instant()=now
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=this
    }
    private fun fixture(block:(Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("proposal-owner-");val path=dir.resolve("synthetic.db");now=at
        try { HistoryStore(path,clock).use { block(path,it) } }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
    private fun setup(store:HistoryStore,date:String="2026-10-18",timed:Boolean=false):AuthoredPlan {
        val author=PlanAuthor("user","synthetic")
        val schedule=if(timed) PlanSchedule("timed",startsLocal="${date}T13:00",endsLocal="${date}T14:00",timeZone="UTC") else PlanSchedule("date_only",date=date,timeZone="UTC")
        val plan=store.createPlan(AuthoredPlanRequest("synthetic",author,listOf(PlannedSession(UUID.randomUUID().toString(),author,"run","synthetic",schedule,
            permissions=AmendmentPermissions(omit=true))),PlanPolicy("UTC",7,0)),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference));store.updateReviewSchedule(0,ReviewScheduleConfiguration(timeZone="UTC"),at,null,null)
        return plan
    }
    private fun source(store:HistoryStore)=store.publishAdaptationResult(PreparedAdaptationResult.prepare(store.captureAdaptationResult(null,now)),now).snapshotId
    private fun input(store:HistoryStore,id:String,outcome:String="select"):AmendmentValidationRequest {
        val inspection=PlanAmendmentPreparation(store,clock).prepare(id).inspection();val packet=inspection.packet!!
        val candidate=packet.candidates.single()
        val phrase=when(outcome) { "select" -> "Candidate ${candidate.candidateId}:";"no_change" -> "No amendment candidate";else -> "Clarification is requested" }
        val draft=AmendmentSelectionDraftV1(AmendmentSelectionContractV1.PROFILE,packet.candidateSetSha256,outcome,
            if(outcome=="select")listOf(candidate.candidateId) else emptyList(),listOf(packet.explanationChoices.single { it.text.startsWith(phrase) }))
        return AmendmentValidationRequest(id,inspection.exactPromptBindingSha256!!,AmendmentSelectionContractV1.encodeDraft(draft))
    }
    private fun service(store:HistoryStore)=PlanAmendmentProposalService(store,clock)
    private fun currentView(view:PlanAmendmentProposalInspection)=view.copy(reviewConstraints=view.reviewConstraints?.copy(asOf=now.toString()))
    private fun context(content:String="synthetic")=AthleteContextRequest("note","user_report","synthetic","2026-10-01",content=content)
    private fun count(path:Path,table:String)=DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
        db.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $table").use { it.next();it.getInt(1) } }
    }

    @Test fun fourOutcomesPersistWithoutPlanCalendarExecutionOrQueueWritesAndSurviveReopen()=fixture { path,store ->
        setup(store);val id=source(store);val plans=store.plans();val queue=store.reviewScheduleState()
        val calendar=PlanService(store,clock).calendar("2026-10-15","2026-10-21","UTC")
        val requests=listOf(input(store,id),input(store,id,"no_change"),input(store,id,"needs_clarification"),input(store,id).copy(rawDraft="{bad PRIVATE_REJECTED_MARKER"))
        val results=requests.map(service(store)::generate)
        assertEquals(listOf("pending","no_change","needs_confirmation","rejected"),results.map { it.state })
        results.forEach { assertEquals("current",it.freshness);assertEquals(if(it.state=="pending") "explicit_exact_proposal_approval_required" else "unavailable_terminal_or_noncurrent",it.approval) }
        assertEquals(plans.plans.single().definition.sessions.single(),results.first().proposal!!.selected.single().before)
        assertTrue(results.last().proposal!!.selected.isEmpty());assertTrue(results.last().proposal!!.explanations.isEmpty())
        assertEquals(plans,store.plans());assertEquals(queue,store.reviewScheduleState());assertEquals(calendar,PlanService(store,clock).calendar("2026-10-15","2026-10-21","UTC"))
        assertEquals(0,count(path,"scoped_execution_bindings"));assertEquals(4,count(path,"plan_amendment_proposals"))
        val before=store.capturePlanAmendment(at).totalChanges
        results.forEach { assertEquals(it,service(store).inspect(it.proposalId)) }
        assertEquals(before,store.capturePlanAmendment(at).totalChanges)
        HistoryStore(path,clock).use { reopened -> results.forEach { assertEquals(it,service(reopened).inspect(it.proposalId)) } }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { s -> s.executeQuery("SELECT projection_json FROM plan_amendment_proposals").use { rows ->
                while(rows.next()) { val text=rows.getString(1);assertFalse(text.contains("PRIVATE_REJECTED_MARKER"));assertFalse(text.contains("\"exactPrompt\":"));assertFalse(text.contains("userPacketJson")) }
            } }
        }
    }

    @Test fun retriesAndExplicitRejectionNeverRenewExpiryOrResurrectPendingState()=fixture { path,store ->
        setup(store);val request=input(store,source(store));val first=service(store).generate(request)
        now=at.plusSeconds(10)
        assertEquals(currentView(first),service(store).generate(request))
        assertFailsWith<PlanConflict> { service(store).reject(first.proposalId,PlanAmendmentProposalRejection("e".repeat(64))) }
        val rejected=service(store).reject(first.proposalId,PlanAmendmentProposalRejection(first.proposalSha256))
        assertEquals("rejected",rejected.state);assertEquals("user_rejected",rejected.reason)
        val changes=store.capturePlanAmendment(now).totalChanges
        now=at.plusSeconds(20)
        assertEquals(currentView(rejected),service(store).reject(first.proposalId,PlanAmendmentProposalRejection(first.proposalSha256)))
        assertEquals(currentView(rejected),service(store).generate(request));assertEquals(changes,store.capturePlanAmendment(now).totalChanges)
        now=Instant.parse(first.expiresUtc)
        val expired=service(store).generate(request)
        assertEquals("expired",expired.state);assertEquals("rejected",expired.disposition);assertEquals("user_rejected",expired.reason);assertNull(expired.proposal)
        assertEquals(first.expiresUtc,expired.expiresUtc);assertEquals(1,count(path,"plan_amendment_proposals"))
    }

    @Test fun dateOnlySourceMidnightAndFirstAffectedTimedStartAreExclusiveExpiryBounds() {
        for(timed in listOf(false,true)) fixture { _,store ->
            setup(store,if(timed) "2026-10-15" else "2026-10-16",timed)
            val first=service(store).generate(input(store,source(store)))
            assertEquals(if(timed) "2026-10-15T13:00:00Z" else "2026-10-16T00:00:00Z",first.expiresUtc)
            assertTrue(Instant.parse(first.expiresUtc)<=at.plusSeconds(86400))
            now=Instant.parse(first.expiresUtc).minusNanos(1);assertEquals("current",service(store).inspect(first.proposalId).freshness)
            now=Instant.parse(first.expiresUtc);assertEquals("expired",service(store).inspect(first.proposalId).freshness)
        }
    }

    @Test fun planRevisionReselectionNewInventoryRulesAndImportsExposeMetadataOnlyStaleWithoutCleanup() {
        for(kind in listOf("plan","selection","context","signal","workload","import")) fixture { path,store ->
            val plan=setup(store);val first=service(store).generate(input(store,source(store)))
            when(kind) {
                "plan" -> store.correctPlan(plan.reference.planId,PlanCorrection(1,1,plan.definition.copy(title="changed")),now)
                "selection" -> { store.selectPlan(PlanSelectionRequest(1,null));store.selectPlan(PlanSelectionRequest(2,plan.reference)) }
                "context" -> store.createContext(context())
                "signal" -> store.saveSignalRule(PersonalSignalRuleRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,false,null),now)
                "workload" -> store.saveWorkloadRule(ExtraWorkloadRuleRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,false,null),now)
                "import" -> store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(syntheticActivity())),now)
            }
            val before=store.capturePlanAmendment(at).totalChanges
            val stale=service(store).inspect(first.proposalId)
            assertEquals("stale",stale.freshness,kind);assertNull(stale.proposal,kind)
            assertEquals(before,store.capturePlanAmendment(at).totalChanges);assertEquals(1,count(path,"plan_amendment_proposals"))
        }
    }

    @Test fun sensitiveContextIntakeCommitmentAndLifecycleCorrectionsPurgeProjectionAndSubmission() {
        for(kind in listOf("context","intake","commitment","lifecycle")) fixture { path,store ->
            setup(store);val c=store.createContext(context("PRIVATE_CONTEXT_MARKER"))
            val intake=store.createIntake(ContextIntakeRequest("PRIVATE_SOURCE_MARKER","user_report","synthetic","2026-10-01"),now)
            val commitment=store.confirmCommitment(CommitmentConfirmation(intake.intakeId,1,CommitmentDefinition("busy","commitment","UTC","2026-10-20T13:00","2026-10-20T14:00")),now)
            val request=input(store,source(store));val first=service(store).generate(request)
            when(kind) {
                "context" -> store.correctContext(c.contextId,context("changed"))
                "intake" -> store.correctIntake(intake.intakeId,ContextIntakeCorrection(1,ContextIntakeRequest("changed","user_report","synthetic","2026-10-01")),now)
                "commitment" -> store.correctCommitment(commitment.reference.commitmentId,CommitmentCorrection(1,CommitmentConfirmation(intake.intakeId,1,commitment.definition.copy(label="changed"))),now)
                "lifecycle" -> store.updateContextLifecycle(c.contextId,ContextLifecycleUpdate(1,"resolved",null),LocalDate.parse("2026-10-15"))
            }
            assertEquals(0,count(path,"plan_amendment_proposals"));assertEquals(0,count(path,"plan_amendment_proposal_state"))
            assertFailsWith<PlanNotFound> { service(store).inspect(first.proposalId) }
            assertFails { service(store).generate(request) }
        }
    }

    @Test fun deletionPreviewBindsCreationAndRejectionThenPurgesAllBoundProposalsWithoutDeletingPlan()=fixture { path,store ->
        setup(store);val context=store.createContext(context());val id=source(store);val request=input(store,id)
        val oldPreview=store.previewContextDeletion(context.contextId,now)
        val first=service(store).generate(request)
        fun apply(preview:ContextDeletionPreview)=store.redactAndDeleteContext(context.contextId,ContextDeletionRequest(preview.expectedRevision,preview.digest,emptyList(),emptyList(),emptyList()),now)
        assertFailsWith<ContextDeletionConflict> { apply(oldPreview) }
        val beforeReject=store.previewContextDeletion(context.contextId,now)
        assertEquals(listOf(first.proposalId),beforeReject.proposalIds)
        service(store).reject(first.proposalId,PlanAmendmentProposalRejection(first.proposalSha256))
        assertFailsWith<ContextDeletionConflict> { apply(beforeReject) }
        val preview=store.previewContextDeletion(context.contextId,now);val plans=store.plans()
        apply(preview)
        assertEquals(plans,store.plans());assertEquals(0,count(path,"plan_amendment_proposals"));assertEquals(0,count(path,"plan_amendment_proposal_state"))
    }

    @Test fun originalCaptureFencesSaveAfterSameOwnerExternalAndDeletionRacesAndClockBoundaries() {
        for(kind in listOf("local","external","delete","expiry","rollback")) fixture { path,store ->
            setup(store);val c=store.createContext(context());val id=source(store)
            val preparation=PlanAmendmentPreparation(store,clock).prepare(id)
            val save=preparation.prepareProposal(store,clock,input(store,id).rawDraft,"a".repeat(64))
            when(kind) {
                "local" -> store.createContext(context("new"))
                "external" -> HistoryStore(path,clock).use { other -> other.createContext(context("external")) }
                "delete" -> store.deleteContext(c.contextId)
                "expiry" -> now=Instant.parse(save.row.expiresUtc)
                "rollback" -> now=at.minusNanos(1)
            }
            assertFailsWith<PlanConflict>(kind) { store.savePlanAmendmentProposal(save,clock) }
            assertEquals(0,count(path,"plan_amendment_proposals"))
        }
    }

    @Test fun inspectionPublicationRacesAndBackwardsClockNeverReturnCachedProjection()=fixture { path,store ->
        setup(store);val first=service(store).generate(input(store,source(store)))
        val capture=store.captureProposalInspection(first.proposalId,now)
        assertTrue(ProposalFreshness.current(capture))
        HistoryStore(path,clock).use { other -> other.createContext(context("external")) }
        assertFailsWith<PlanConflict> { store.finishProposalInspection(capture,true,clock) }
        now=at.minusNanos(1);val stale=service(store).inspect(first.proposalId)
        assertEquals("stale",stale.freshness);assertNull(stale.proposal)
    }

    @Test fun stateInsertFailureRollsBackProjectionAndAllowsFreshRetry()=fixture { path,store ->
        setup(store);val request=input(store,source(store))
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER synthetic_proposal_failure BEFORE INSERT ON plan_amendment_proposal_state BEGIN SELECT RAISE(ABORT,'synthetic'); END") }
            assertFailsWith<java.sql.SQLException> { service(store).generate(request) }
            assertEquals(0,count(path,"plan_amendment_proposals"));assertEquals(0,count(path,"plan_amendment_proposal_state"))
            db.createStatement().use { it.execute("DROP TRIGGER synthetic_proposal_failure") }
        }
        assertEquals("pending",service(store).generate(request).state)
    }

    @Test fun importedRemovalCascadesSnapshotsProjectionsAndSubmissionWithoutRemovingAuthoredPlan()=fixture { path,store ->
        setup(store);val request=input(store,source(store));val first=service(store).generate(request)
        val plans=store.plans()
        store.removeImports()
        assertEquals(0,count(path,"scoped_evidence_snapshots"));assertEquals(0,count(path,"plan_amendment_proposals"))
        assertEquals(0,count(path,"plan_amendment_proposal_state"));assertEquals(plans,store.plans())
        assertFailsWith<PlanNotFound> { service(store).inspect(first.proposalId) }
        assertFails { service(store).generate(request) }
    }

    @Test fun ephemeralReviewIncludesCompleteLocalConstraintsOutsideImmutableSourcePacketAndNeverPersistsThemAgain()=fixture { path,store ->
        setup(store)
        val intake=store.createIntake(ContextIntakeRequest("PRIVATE_FUTURE_CONSTRAINT_MARKER","user_report","synthetic","2026-10-01"),now)
        val commitment=store.confirmCommitment(CommitmentConfirmation(intake.intakeId,1,CommitmentDefinition("Outside captured horizon","commitment","UTC","2027-01-01T09:00","2027-01-01T10:00")),now)
        val id=source(store)
        assertTrue(store.storedScopedEvidence(id).snapshot.packet!!.commitments.flatMap { it.occurrences }.none { it.reference.commitmentId==commitment.reference.commitmentId })
        val made=service(store).generate(input(store,id));val details=made.reviewConstraints!!
        assertEquals(made.proposalId,details.proposalId);assertEquals(made.proposalSha256,details.proposalSha256)
        assertEquals(made.proposal!!.policyInputSha256,details.policyInputSha256)
        assertEquals(listOf(commitment),details.commitments)
        assertEquals(store.plans().plans.single().definition.sessions.map { it.schedule },details.occupancy.map { it.schedule })
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            s.executeQuery("SELECT projection_json FROM plan_amendment_proposals").use { it.next();assertFalse(it.getString(1).contains("Outside captured horizon")) }
        } }
        now=Instant.parse(made.expiresUtc)
        assertNull(service(store).inspect(made.proposalId).reviewConstraints)
    }
}
