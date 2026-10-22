package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

class ImportedEvidenceRemovalTest {
    private val at = Instant.parse("2020-06-01T08:01:00Z")
    private fun fixture(block: (Path, HistoryStore) -> Unit) {
        val dir = Files.createTempDirectory("import-removal-"); val path = dir.resolve("history.sqlite3")
        try { HistoryStore(path).use { store ->
            store.updateReviewSchedule(0, ReviewScheduleConfiguration(enabled=true,timeZone="UTC",queuePolicy=ReviewQueuePolicy(0,0,60)),at,null,null)
            store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(syntheticActivity().copy(startLocal="2020-06-01T07:00:00",
                startInstant="2020-06-01T07:00:00Z",timeZone="UTC",utcOffset="Z",timeContext="upstream_instant"))),at)
            block(path,store)
        } } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }
    private fun count(path: Path, table: String) = DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
        db.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $table").use { r -> r.next(); r.getInt(1) } }
    }
    private fun snapshot(store: HistoryStore): ScopedEvidenceSnapshot {
        val state=store.reviewScheduleState(); val rev=store.scopedRoutingRevisions()
        val scope=ReviewScopeResolver.resolve(ScopedReviewRequest(scope="day",anchorDate="2020-06-01"),at,"UTC")
        val intent=ScopedQueueIntent(ScopedDescriptiveContractV1.PROFILE,"morning",state.version,scope,rev.source,rev.planReason)
        val job=store.updateReviewQueue { current ->
            val q=ReviewQueueRules.enqueueScoped(current.queue,listOf(ReviewQueueRules.ScopedQueueSubmission(intent,
                AnalysisClaims.hash("synthetic-$at"),listOf("purpose_slot"),manual=true)),current.configuration.queuePolicy,at)
            val claimed=ReviewQueueRules.claim(q,at); current.copy(queue=claimed) to requireNotNull(claimed.running)
        }
        val capture=store.captureScopedQueueJob(job,at)
        return store.publishScopedQueueJob(capture,prepareScopedEvidence(capture.request,capture.evidence,
            ScopedTrends.report(capture.evidence.read.scope,capture.evidence.read.history,capture.evidence.read.statuses)),at)
    }

    @Test fun `removes factual packets and cascaded copies but preserves user authored records`()=fixture { path,store ->
        val plan=store.createPlan(AuthoredPlanRequest("Synthetic",PlanAuthor("user","test"), emptyList()),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference))
        val context=store.createContext(AthleteContextRequest("note","user_report","test","2020-06-01",content="authored context"))
        val intake=store.createIntake(ContextIntakeRequest("original","user_report","test","2020-06-01"),at)
        val snap=snapshot(store)
        val execution=ScopedExecutionService(store,ReviewTestClock(at)).prepare(ScopedDescriptiveContractV1.PROFILE,
            ScopedExecutionSource(snapshotId=snap.snapshotId),ConnectedReviewProviderSelection("synthetic","model","local"))
        val source=store.captureScopedExecutionSource(execution.binding.profile,execution.binding.source)
        val unpublished=PreparedScopedExecution.prepare(execution.binding.copy(executionId=UUID.randomUUID().toString(),generation=2),source)
        val input=AnalysisInput(1,"a".repeat(64),at.toString(),null,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        val legacy=store.createConnectedReviewSnapshot(ConnectedReviewBinding("synthetic-removal",input,
            retrieveAthleteContext(listOf(context),ContextRetrievalQuery(LocalDate.parse("2020-06-01"),hardPacketLimit=100)),
            "2020-06-01","2020-06-01",null,execution.binding.providerSelection,InterpretationContractV1.PROFILE),at)
        store.publishConnectedReview(legacy.snapshotId,ValidatedConnectedReviewOutput("synthetic-derived-output"),legacy.evidenceDigest,
            legacy.context,legacy.provider,legacy.model,legacy.contractVersion)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.prepareStatement("UPDATE scoped_execution_state SET state='PUBLISHED',validated_output_json=? WHERE execution_id=?").use { s ->
                s.setString(1,"synthetic-derived-output");s.setString(2,execution.binding.executionId);s.executeUpdate()
            }
            db.prepareStatement("INSERT INTO scoped_queue_preview_ownership(execution_id,job_id) VALUES(?,?)").use { s ->
                s.setString(1,execution.binding.executionId);s.setString(2,store.reviewScheduleState().queue.outcomes.single().id);s.executeUpdate()
            }
        }
        assertTrue(count(path,"scoped_execution_bindings")>0)
        assertTrue(count(path,"scoped_evidence_records")>0,"Fixture must populate record-membership rows")
        assertTrue(count(path,"scoped_evidence_dependencies")>0,"Fixture must populate dependency rows")
        store.removeImports()
        assertEquals(0,count(path,"scoped_evidence_snapshots")); assertEquals(0,count(path,"connected_review_snapshots"))
        assertEquals(0,count(path,"scoped_execution_bindings")); assertEquals(0,count(path,"scoped_evidence_records"))
        assertEquals(plan,store.plan(plan.reference.planId)); assertEquals(context,store.contexts(LocalDate.parse("2020-06-01")).single())
        assertEquals(intake,store.intakeHistory(intake.intakeId).single())
        assertFailsWith<ScopedEvidenceNotFound> { store.storedScopedEvidence(snap.snapshotId) }
        assertFailsWith<ConnectedReviewNotFound> { ScopedExecutionService(store).inspect(execution.binding.executionId) }
        assertFailsWith<ConnectedReviewNotFound> { store.connectedReviewStatus(legacy.snapshotId) }
        assertFailsWith<ScopedEvidenceConflict> { store.saveScopedExecution(unpublished) }
        for(table in listOf("connected_review_snapshot_state","connected_review_context_refs","scoped_execution_state","scoped_evidence_dependencies",
            "scoped_queue_preview_ownership","scoped_queue_snapshot_ownership"))assertEquals(0,count(path,table))
        assertTrue(store.reviewScheduleState().queue.outcomes.isEmpty())
    }

    @Test fun `source snapshot claim is revoked before deletion and unresolved occupancy remains`()=fixture { _,store ->
        val snap=snapshot(store)
        val binding=ScopedExecutionService(store,ReviewTestClock(at)).prepare(ScopedDescriptiveContractV1.PROFILE,
            ScopedExecutionSource(snapshotId=snap.snapshotId),ConnectedReviewProviderSelection("synthetic","model","local")).binding
        val capture=store.captureScopedExecution(binding.executionId)
        val resource=PersonalResourceIdentity("domain","runtime",binding.providerSelection)
        val observation=PersonalResourceObservation(resource,at,at.plusSeconds(600),true)
        val claim=assertNotNull(store.reservePersonalReview(capture,capture.current(),store.personalOwnerEpoch(),observation,resource,Clock.fixed(at,ZoneOffset.UTC),600_000))
        assertTrue(store.beginPersonalStart(claim,observation,Clock.fixed(at,ZoneOffset.UTC)))
        val repository=ScopedExecutionRepository(store,claim,ReviewTestClock(at))
        assertNotNull(repository.claimConnectedReview(binding.executionId))
        val running=store.captureScopedExecution(binding.executionId)
        store.removeImports()
        val revoked=assertNotNull(store.personalRuntimeLease(claim.claimId))
        assertEquals(PersonalRuntimePhase.STOPPING,revoked.phase); assertFalse(revoked.publicationAllowed)
        assertEquals("domain",revoked.claim.resource.domain)
        assertFalse(store.beginPersonalStart(claim,observation,Clock.fixed(at,ZoneOffset.UTC)))
        assertFalse(store.authorizePersonalAttempt(claim,1,observation,ReviewTestClock(at)))
        assertEquals(claim,store.unresolvedPersonalReviews().single().claim)
        assertFailsWith<ConnectedReviewNotFound> { store.publishPersonalReview(claim,running,true,ValidatedConnectedReviewOutput("late synthetic output"),ReviewTestClock(at)) }
        assertFailsWith<ConnectedReviewNotFound> { store.captureScopedExecution(binding.executionId) }
        val note=store.createIntake(ContextIntakeRequest("preserved synthetic original","user_report","synthetic","2020-06-01"),at)
        val next=ScopedExecutionService(store,ReviewTestClock(at)).prepare(ContextIntakeSuggestionContractV1.PROFILE,
            ScopedExecutionSource(intakeId=note.intakeId,intakeRevision=1),binding.providerSelection).binding
        val nextCapture=store.captureScopedExecution(next.executionId)
        assertNull(store.reservePersonalReview(nextCapture,nextCapture.current(),store.personalOwnerEpoch(),observation,resource,ReviewTestClock(at),600_000))
    }

    @Test fun `snapshotless intake binding survives source purge`()=fixture { _,store ->
        val intake=store.createIntake(ContextIntakeRequest("original","user_report","test","2020-06-01"),at)
        val binding=ScopedExecutionService(store,ReviewTestClock(at)).prepare(ContextIntakeSuggestionContractV1.PROFILE,
            ScopedExecutionSource(intakeId=intake.intakeId,intakeRevision=intake.revision),ConnectedReviewProviderSelection("synthetic","model","local")).binding
        store.removeImports()
        assertEquals(binding,ScopedExecutionService(store).inspect(binding.executionId).binding)
        assertEquals("original",store.intakeHistory(intake.intakeId).single().content)
    }

    @Test fun `late sql failure rolls purge and revocation back atomically`()=fixture { path,store ->
        val snap=snapshot(store)
        val binding=ScopedExecutionService(store,ReviewTestClock(at)).prepare(ScopedDescriptiveContractV1.PROFILE,
            ScopedExecutionSource(snapshotId=snap.snapshotId),ConnectedReviewProviderSelection("synthetic","model","local")).binding
        val capture=store.captureScopedExecution(binding.executionId)
        val resource=PersonalResourceIdentity("domain","runtime",binding.providerSelection)
        val observation=PersonalResourceObservation(resource,at,at.plusSeconds(600),true)
        val claim=assertNotNull(store.reservePersonalReview(capture,capture.current(),store.personalOwnerEpoch(),observation,resource,ReviewTestClock(at),600_000))
        assertTrue(store.beginPersonalStart(claim,observation,ReviewTestClock(at)))
        val lease=store.personalRuntimeLease(claim.claimId)
        val before=store.reviewScheduleState();val revisions=store.scopedRoutingRevisions()
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use {
            it.execute("CREATE TRIGGER reject_import_delete BEFORE DELETE ON activities BEGIN SELECT RAISE(ABORT, 'injected'); END")
        } }
        assertFailsWith<java.sql.SQLException> { store.removeImports() }
        assertEquals(1,count(path,"scoped_evidence_snapshots")); assertEquals(1,count(path,"scoped_execution_bindings"))
        assertEquals(before,store.reviewScheduleState())
        assertEquals(revisions,store.scopedRoutingRevisions());assertEquals(lease,store.personalRuntimeLease(claim.claimId))
        assertEquals(binding,ScopedExecutionService(store).inspect(binding.executionId).binding)
    }

    @Test fun `repeat removal is safe and preserves purpose high water`()=fixture { path,store ->
        val before=mapOf(UUID.randomUUID().toString() to "2020-06-01")
        store.updateReviewQueue { it.copy(purposeHighWater=before) to Unit }
        snapshot(store)
        store.removeImports(); val once=store.reviewScheduleState()
        store.removeImports(); val twice=store.reviewScheduleState()
        assertEquals(before,once.purposeHighWater); assertEquals(before,twice.purposeHighWater)
        assertEquals(0,count(path,"scoped_evidence_snapshots")); assertEquals(0,count(path,"connected_review_snapshots"))
        store.close()
        HistoryStore(path).use { reopened -> assertEquals(before,reopened.reviewScheduleState().purposeHighWater);assertTrue(reopened.reviewScheduleState().queue.outcomes.isEmpty()) }
    }

    @Test fun `all revisions of match bearing lineages are purged without resurrecting skips in deselected plans`()=fixture { path,store ->
        val clock=ReviewTestClock(Instant.parse("2020-06-03T08:00:00Z"));val service=PlanService(store,clock)
        val linked=UUID.randomUUID().toString();val skipOnly=UUID.randomUUID().toString()
        val plan=service.create(AuthoredPlanRequest("Synthetic",PlanAuthor("user","Synthetic"),listOf(linked,skipOnly).map { id ->
            PlannedSession(id,PlanAuthor("user","Synthetic"),"Ride","Synthetic",PlanSchedule("date_only",date="2020-06-02",timeZone="UTC"))
        },PlanPolicy("UTC",30,0)))
        service.select(PlanSelectionRequest(0,plan.reference))
        val query=PlanReconciliationQuery("2020-06-01","2020-06-02","UTC")
        fun write(session: String,action: String): PlanReconciliationConfirmationRequest {
            val view=service.reconciliation(query)
            val input=PlanReconciliationConfirmationRequest(UUID.randomUUID().toString(),assertNotNull(view.selection.selected),session,action,
                if(action=="match") view.activities.single().activity.activityId else null,view.selection.generation,
                view.sessions.single { it.sessionId==session }.confirmation?.confirmationId,view.digest,view.asOf,query)
            service.confirmReconciliation(input);return input
        }
        val oldSkip=write(linked,"skip");val independent=write(skipOnly,"skip")
        write(linked,"match");write(linked,"clear")
        val selected=service.list().selection
        service.correct(plan.reference.planId,PlanCorrection(1,selected.generation,plan.definition.copy(title="Synthetic corrected")))
        write(linked,"skip")
        service.select(PlanSelectionRequest(service.list().selection.generation,null))
        assertEquals(5,count(path,"plan_reconciliation_confirmations"))
        store.removeImports()
        assertEquals(1,count(path,"plan_reconciliation_confirmations"))
        assertNull(store.reconciliationReceipt(oldSkip));assertTrue(assertNotNull(store.reconciliationReceipt(independent)).replayed)
        assertEquals(2,service.get(plan.reference.planId).reference.revision)
        service.select(PlanSelectionRequest(service.list().selection.generation,PlanRef(plan.reference.planId,2)))
        assertNull(service.reconciliation(query).sessions.single { it.sessionId==linked }.confirmation)
        assertFailsWith<PlanConflict> { service.confirmReconciliation(oldSkip) }
    }
}
