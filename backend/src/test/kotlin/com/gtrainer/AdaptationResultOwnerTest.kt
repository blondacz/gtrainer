package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdaptationResultOwnerTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private var now=at
    private val clock=object:Clock() {
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:java.time.ZoneId):Clock=this
        override fun instant()=now
    }
    private fun fixture(block:(Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("adaptation-result-owner-");val path=dir.resolve("history.db")
        now=at
        try { HistoryStore(path,clock).use { block(path,it) } }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
    private fun context(value:String)=AthleteContextRequest("restriction","user_report","synthetic","2026-10-01",content="synthetic restriction",
        restrictionKind="maximum_duration",restrictionValue=value,restrictionUnit="minutes")
    private fun setup(store:HistoryStore,sessions:Int=1):String {
        val restriction=store.createContext(context("60"))
        val plan=store.createPlan(AuthoredPlanRequest("Synthetic",PlanAuthor("user","synthetic"),(1..sessions).map {
            PlannedSession(UUID.randomUUID().toString(),PlanAuthor("user","synthetic"),"ride","easy",PlanSchedule("date_only",date="2026-10-18",timeZone="UTC")) },PlanPolicy("UTC",7,0)),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference))
        store.updateReviewSchedule(0,ReviewScheduleConfiguration(enabled=true,timeZone="UTC",queuePolicy=ReviewQueuePolicy(0,0,60)),at,null,null)
        return restriction.contextId
    }
    private fun admit(store:HistoryStore,time:Instant=at)=store.commitSignalAdaptation(
        PreparedSignalAdaptation.prepare(store.captureSignalAdaptation(time)),Clock.fixed(time,ZoneOffset.UTC))
    private fun prepare(store:HistoryStore,time:Instant=at)=PreparedAdaptationResult.prepare(store.captureAdaptationResult(null,time))
    private fun run(store:HistoryStore,time:Instant=at):ScopedEvidenceSnapshot {
        store.updateReviewQueue { state -> state.copy(queue=ReviewQueueRules.claim(state.queue,time)) to Unit }
        val job=store.reviewScheduleState().queue.running!!
        runBlocking { PersonalScopedQueueAdapter(store,Clock.fixed(time,ZoneOffset.UTC)).prepare(job) }
        return store.storedScopedEvidence(store.reviewScheduleState().queue.outcomes.last().snapshotId!!).snapshot
    }

    private fun brief(store:HistoryStore,purpose:String,provider:ConnectedReviewProviderSelection?=null):ScopedEvidenceSnapshot {
        val revisions=store.scopedRoutingRevisions()
        val state=store.reviewScheduleState()
        val intent=ScopedQueueIntent(ScopedDescriptiveContractV1.PROFILE,purpose,state.version,
            ReviewScopeResolver.resolve(ScopedReviewRequest(scope="day",wellnessPolicy="assessment"),now,"UTC"),revisions.source,revisions.planReason,provider)
        store.updateReviewQueue { current -> current.copy(queue=ReviewQueueRules.enqueueScoped(current.queue,listOf(
            ReviewQueueRules.ScopedQueueSubmission(intent,UUID.randomUUID().toString(),listOf("purpose_slot"))),current.configuration.queuePolicy,now)) to Unit }
        return run(store,now)
    }

    private fun count(path:Path,table:String)=java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
        db.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $table").use { r -> r.next();r.getInt(1) } }
    }

    @Test fun `existing worker prepares canonical adaptation facts and fulfills without changing plan`()=fixture { _,store ->
        val id=setup(store);store.correctContext(id,context("30"));admit(store)
        val selection=store.plans().selection
        val requested=store.reviewScheduleState().queue.pending.single().scopedIntent!!.adaptation!!
        val snapshot=run(store)
        assertEquals("PREPARED",snapshot.state)
        assertEquals(requested.workVersionSha256,snapshot.packet!!.adaptation!!.workKey)
        assertEquals(1,snapshot.packet.adaptation!!.constraintChanges.size)
        assertEquals(AdaptationFacts.VERSION,snapshot.packet.adaptation.version)
        assertTrue(snapshot.packet.adaptation.reasons.single { it.kind=="changed_constraint" }.provenance.single().startsWith("constraint_event/"))
        assertTrue("adaptation_result/${requested.workVersionSha256}" in snapshot.manifest.included)
        assertTrue(snapshot.packet.facts.sessions.isEmpty())
        assertEquals(selection,store.plans().selection)
        assertEquals("factual_prepared",store.reviewScheduleState().queue.outcomes.single().status)
        assertTrue(admit(store).admittedReasonIds.isEmpty())
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
    }

    @Test fun `canonical reuse survives history eviction and reopen without another admission`()=fixture { path,store ->
        val id=setup(store);store.correctContext(id,context("30"));admit(store)
        val original=run(store)
        store.updateReviewQueue { it.copy(queue=ReviewQueueState()) to Unit }
        val reused=store.publishAdaptationResult(prepare(store),at)
        assertEquals(original.snapshotId,reused.snapshotId)
        HistoryStore(path,Clock.fixed(at,ZoneOffset.UTC)).use { reopened ->
            assertEquals(original.snapshotId,reopened.publishAdaptationResult(prepare(reopened),at).snapshotId)
            assertTrue(admit(reopened).admittedReasonIds.isEmpty())
            assertTrue(reopened.reviewScheduleState().queue.pending.isEmpty())
        }
    }

    @Test fun `unrelated display day recording does not alter canonical result identity`()=fixture { _,store ->
        val id=setup(store);store.correctContext(id,context("30"));admit(store)
        val original=run(store)
        val later=at.plusSeconds(60)
        val unrelated=syntheticActivity().copy(sourceRecordId="unrelated",sport="swim",startLocal="2026-10-15T10:00:00",
            startInstant="2026-10-15T10:00:00Z",timeContext="upstream_instant")
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(unrelated)),later)
        val prepared=prepare(store,later)
        assertEquals(original.packet!!.adaptation!!.workKey,prepared.admission.work!!.key())
        assertEquals(original.snapshotId,store.publishAdaptationResult(prepared,later).snapshotId)
    }

    @Test fun `changed canonical proof and midnight fence stale result publication`()=fixture { _,store ->
        val id=setup(store);store.correctContext(id,context("30"));admit(store)
        val initial=prepare(store)
        store.correctContext(id,context("20"))
        assertFailsWith<PlanConflict> { store.publishAdaptationResult(initial,at) }
        assertNotEquals(initial.admission.work!!.key(),prepare(store).admission.work!!.key())
        val current=prepare(store)
        assertFailsWith<ScopedEvidenceConflict> { store.publishAdaptationResult(current,Instant.parse("2026-10-16T00:00:00Z")) }
    }

    @Test fun `context deletion removes historical endpoint result and keeps fulfilled admission facts`()=fixture { _,store ->
        val id=setup(store);store.correctContext(id,context("30"));admit(store)
        val result=run(store)
        val admissions=store.captureSignalAdaptation(at).root.admissions
        val preview=store.previewContextDeletion(id,at)
        assertTrue(result.snapshotId in preview.snapshotIds)
        store.redactAndDeleteContext(id,ContextDeletionRequest(preview.expectedRevision,preview.digest,emptyList(),emptyList(),emptyList()),at)
        assertFailsWith<ScopedEvidenceNotFound> { store.storedScopedEvidence(result.snapshotId) }
        assertEquals(admissions,store.captureSignalAdaptation(at).root.admissions)
    }

    @Test fun `morning evening and baseline only briefs reuse one durable adaptation result`()=fixture { path,store ->
        setup(store)
        val morning=brief(store,"morning")
        val evening=brief(store,"evening")
        assertEquals(morning.packet!!.adaptation,evening.packet!!.adaptation)
        assertEquals(1,count(path,"adaptation_results"))
        assertTrue(store.captureSignalAdaptation(at).root.admissions.isEmpty())
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
        val key=morning.packet.adaptation!!.workKey
        assertEquals(key,store.publishAdaptationResult(prepare(store),at).packet!!.adaptation!!.workKey)
    }

    @Test fun `exact referenced result reaches provider preview and deletion closure without inference`()=fixture { path,store ->
        val id=setup(store);store.correctContext(id,context("30"));admit(store)
        val result=run(store)
        val daily=brief(store,"morning",ConnectedReviewProviderSelection("synthetic","synthetic-model","hosted"))
        val outcome=store.reviewScheduleState().queue.outcomes.last()
        assertEquals("awaiting_consent",outcome.preview!!.state)
        val execution=ScopedExecutionService(store,clock).inspect(outcome.preview.executionId!!)
        assertEquals("IN_FLIGHT",execution.state)
        assertEquals(result.packet!!.adaptation,daily.packet!!.adaptation)
        assertEquals("2026-10-16T00:00:00Z",execution.binding.sourceValidUntilUtc)
        val preview=store.previewContextDeletion(id,at)
        assertTrue(preview.snapshotIds.containsAll(listOf(result.snapshotId,daily.snapshotId)))
        assertTrue(outcome.preview.executionId in preview.executionIds)
        store.redactAndDeleteContext(id,ContextDeletionRequest(preview.expectedRevision,preview.digest,emptyList(),emptyList(),emptyList()),at)
        assertEquals(0,count(path,"adaptation_results"))
        assertFailsWith<ScopedEvidenceNotFound> { store.storedScopedEvidence(daily.snapshotId) }
        assertFailsWith<ConnectedReviewNotFound> { ScopedExecutionService(store,clock).inspect(outcome.preview.executionId!!) }
    }

    @Test fun `result inspection is read only and temporal expiry fences preview insertion and inspection`()=fixture { path,store ->
        setup(store)
        val result=store.publishAdaptationResult(prepare(store),at)
        val source=ScopedExecutionSource(snapshotId=result.snapshotId)
        val selection=ConnectedReviewProviderSelection("synthetic","synthetic-model","hosted")
        val service=ScopedExecutionService(store,clock)
        val execution=service.prepare(ScopedDescriptiveContractV1.PROFILE,source,selection)
        val prepared=service.assemble(ScopedDescriptiveContractV1.PROFILE,source,selection)
        val capture=store.captureScopedEvidenceInspection(result.snapshotId,at)
        assertEquals(store.storedScopedEvidence(result.snapshotId).bindingSha256,capture.currentDigest())
        val changes=capture.totalChanges
        repeat(3) { assertEquals("IN_FLIGHT",service.inspect(execution.binding.executionId).state) }
        assertEquals(changes,store.captureScopedEvidenceInspection(result.snapshotId,at).totalChanges)
        now=Instant.parse("2026-10-16T00:00:00Z")
        assertFailsWith<ScopedEvidenceConflict> { store.saveScopedExecution(prepared) }
        assertEquals("STALE",service.inspect(execution.binding.executionId).state)
        assertEquals(1,count(path,"scoped_execution_bindings"))
    }

    @Test fun `mandatory overflow does not fulfill or erase an admitted episode`()=fixture { path,store ->
        val id=setup(store,40);store.correctContext(id,context("30"));admit(store)
        val snapshot=run(store)
        assertEquals("BLOCKED",snapshot.state)
        assertTrue("mandatory_or_explicit_subject_packet_overflow" in snapshot.manifest.blockedReasons)
        assertEquals(0,count(path,"adaptation_results"))
        assertTrue(admit(store).admittedReasonIds.isEmpty())
        assertEquals(1,store.reviewScheduleState().queue.pending.size)
        assertEquals(1,store.captureSignalAdaptation(at).root.admissions.size)
    }

    @Test fun `explicit armed tick routes adaptation but status restart and pause never admit`()=fixture { _,store -> runBlocking {
        val id=setup(store)
        val model=SyntheticReviewModel()
        val reviews=ReviewInterpretationService(listOf(model),ReviewExecutionPolicy(allowCorrectiveAttempt=true),{ true })
        try {
            val scheduler=ReviewScheduler(store,reviews,clock,durableQueue=true)
            val configuration=store.reviewScheduleState().configuration
            scheduler.configure(ReviewScheduleUpdate(store.reviewScheduleState().version,configuration))
            store.correctContext(id,context("30"))
            val changes=store.captureSignalAdaptation(at).root.totalChanges
            repeat(3) { assertEquals("inspection_only_not_admission",scheduler.status().adaptationRouting) }
            assertEquals(changes,store.captureSignalAdaptation(at).root.totalChanges)
            assertTrue(store.captureSignalAdaptation(at).root.admissions.isEmpty())
            assertEquals("factual_adaptation_queued",scheduler.tick().adaptationRouting)
            assertEquals(1,store.captureSignalAdaptation(at).root.admissions.size)
            store.correctContext(id,context("20"))
            val restarted=ReviewScheduler(store,reviews,clock,durableQueue=true)
            assertEquals("restart_requires_explicit_rearm",restarted.tick().adaptationRouting)
            assertEquals(1,store.captureSignalAdaptation(at).root.admissions.size)
            scheduler.configure(ReviewScheduleUpdate(store.reviewScheduleState().version,configuration.copy(paused=true)))
            assertEquals("schedules_disabled_or_paused",scheduler.tick().adaptationRouting)
            assertEquals(1,store.captureSignalAdaptation(at).root.admissions.size)
            assertTrue(model.packets.isEmpty())
        } finally { reviews.close() }
    } }

    @Test fun `overlapping manual scopes share adaptation facts and optional wellness refusal does not leak`()=fixture { path,store -> runBlocking {
        setup(store)
        val source=object:HistorySource {
            override val sourceId="synthetic"
            override suspend fun activities(range:ReadRange):ReadResult<ActivityRecord> = error("No reads allowed")
            override suspend fun wellness(range:ReadRange):ReadResult<WellnessRecord> = error("No reads allowed")
        }
        val service=HistoryService(store,source,clock)
        val first=service.prepareEvidence(ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="custom",oldest="2026-10-10",newest="2026-10-15"),purpose="adaptation"))
        val overlap=service.prepareEvidence(ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="custom",oldest="2026-10-12",newest="2026-10-15"),purpose="adaptation"))
        assertEquals(first.snapshot.packet!!.adaptation,overlap.snapshot.packet!!.adaptation)
        assertEquals(1,count(path,"adaptation_results"))
        assertEquals("PREPARED",service.inspectEvidence(first.snapshot.snapshotId).state)
        for(policy in listOf("off","display_only")) {
            val blocked=service.prepareEvidence(ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="day",wellnessPolicy=policy),purpose="adaptation"))
            assertEquals("BLOCKED",blocked.snapshot.state)
            assertEquals(null,blocked.snapshot.packet)
            assertTrue("adaptation_requires_assessment_context" in blocked.snapshot.manifest.blockedReasons)
        }
        assertEquals(1,count(path,"adaptation_results"))
        assertTrue(store.captureSignalAdaptation(at).root.admissions.isEmpty())
    } }

    @Test fun `external source changes fence prepared preview and checked inspection`()=fixture { path,store ->
        val id=setup(store);store.correctContext(id,context("30"));admit(store)
        val result=run(store)
        val service=ScopedExecutionService(store,clock)
        val source=ScopedExecutionSource(snapshotId=result.snapshotId)
        val selection=ConnectedReviewProviderSelection("synthetic","synthetic-model","hosted")
        val execution=service.prepare(ScopedDescriptiveContractV1.PROFILE,source,selection)
        val prepared=service.assemble(ScopedDescriptiveContractV1.PROFILE,source,selection)
        val checked=store.captureScopedExecution(execution.binding.executionId)
        assertTrue(checked.current())
        val inspection=store.captureScopedEvidenceInspection(result.snapshotId,at)
        HistoryStore(path,clock).use { external -> external.correctContext(id,context("20")) }
        assertFailsWith<ScopedEvidenceConflict> { store.saveScopedExecution(prepared) }
        assertFailsWith<ScopedEvidenceConflict> { store.finishScopedExecutionCheck(checked,true) }
        assertFailsWith<ScopedEvidenceConflict> { store.finishScopedEvidenceInspection(result.snapshotId,inspection.totalChanges,true,
            inspection.validUntil,inspection.scoped!!.adaptationReference!!.current) }
        assertEquals("STALE",service.inspect(execution.binding.executionId).state)
    }

    @Test fun `result index failure rolls back publication and retries admitted work without another admission`()=fixture { path,store ->
        val id=setup(store);store.correctContext(id,context("30"));admit(store)
        store.updateReviewQueue { state -> state.copy(queue=ReviewQueueRules.claim(state.queue,at)) to Unit }
        val job=store.reviewScheduleState().queue.running!!
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use {
            it.execute("CREATE TRIGGER synthetic_result_failure BEFORE INSERT ON adaptation_results BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        } }
        val prepared=PreparedAdaptationResult.prepare(store.captureAdaptationResult(job,at))
        assertFailsWith<java.sql.SQLException> { store.publishAdaptationResult(prepared,at) }
        assertEquals(0,count(path,"scoped_evidence_snapshots"))
        assertEquals(0,count(path,"adaptation_results"))
        assertEquals(job,store.reviewScheduleState().queue.running)
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use {
            it.execute("DROP TRIGGER synthetic_result_failure")
        } }
        store.updateReviewQueue { it.copy(queue=ReviewQueueState()) to Unit }
        assertTrue(admit(store).admittedReasonIds.isEmpty())
        assertEquals(1,store.reviewScheduleState().queue.pending.size)
        assertEquals("PREPARED",run(store).state)
        assertEquals(1,store.captureSignalAdaptation(at).root.admissions.size)
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
    }

    @Test fun `shared snapshot capacity preserves indexed results and refuses new work without eviction`()=fixture { path,store ->
        val id=setup(store);store.correctContext(id,context("30"));admit(store)
        val original=run(store)
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.autoCommit=false
            db.prepareStatement("INSERT INTO scoped_evidence_snapshots SELECT ?,request_json,binding_sha256,packet_sha256,packet_json,manifest_json,created_utc FROM scoped_evidence_snapshots WHERE snapshot_id=?").use { p ->
                repeat(999) { p.setString(1,UUID.randomUUID().toString());p.setString(2,original.snapshotId);p.addBatch() };p.executeBatch()
            }
            db.commit()
        }
        assertEquals(original.snapshotId,store.publishAdaptationResult(prepare(store),at).snapshotId)
        store.correctContext(id,context("20"));assertEquals(1,admit(store).admittedReasonIds.size)
        assertFailsWith<IllegalArgumentException> { store.publishAdaptationResult(prepare(store),at) }
        assertEquals(1000,count(path,"scoped_evidence_snapshots"))
        assertEquals(1,count(path,"adaptation_results"))
        assertEquals(original.snapshotId,store.storedScopedEvidence(original.snapshotId).snapshot.snapshotId)
        assertEquals(1,store.reviewScheduleState().queue.pending.size)
        assertEquals(2,store.captureSignalAdaptation(at).root.admissions.size)
    }

    @Test fun `canonical commitment facts retain exact source binding and correction invalidates the result`()=fixture { _,store ->
        setup(store)
        val source=store.createIntake(ContextIntakeRequest("synthetic commitment source","user_report","synthetic","2026-10-15"),at)
        val confirmation=CommitmentConfirmation(source.intakeId,1,
            CommitmentDefinition("Synthetic","commitment","UTC","2026-10-18T10:00","2026-10-18T11:00"))
        store.confirmCommitment(confirmation,at)
        admit(store)
        val result=run(store)
        assertTrue(result.packet!!.sourceBindings.any { it.intakeId==source.intakeId && it.currentRevision==1 })
        assertTrue(result.manifest.dependencies.any { it.kind=="intake" && it.identity==source.intakeId && it.revision==1 })
        assertEquals(store.storedScopedEvidence(result.snapshotId).bindingSha256,
            store.captureScopedEvidenceInspection(result.snapshotId,at).currentDigest())
        store.correctIntake(source.intakeId,ContextIntakeCorrection(1,
            ContextIntakeRequest("corrected synthetic source","user_report","synthetic","2026-10-15")),at)
        kotlin.test.assertFails { store.captureScopedEvidenceInspection(result.snapshotId,at).currentDigest() }
    }

    @Test fun `final adaptation context retains older linked symptom and exact source dependency`()=fixture { _,store ->
        val restrictionId=setup(store)
        val source=store.createIntake(ContextIntakeRequest("synthetic symptom source","user_report","synthetic","2026-10-01"),at)
        val symptom=AthleteContextRequest("symptom","user_report","synthetic","2026-10-01",applicableFrom="2026-10-16",
            applicableUntil="2026-10-25",content="synthetic symptom")
        val confirmed=store.confirmIntake(source.intakeId,ContextConfirmationRequest(1,
            contexts=listOf(ContextConfirmationItem(symptom)),confirmationId=UUID.randomUUID().toString()),at).contexts.single()
        repeat(21) { index -> store.createContext(AthleteContextRequest("note","user_report","synthetic","2026-10-15",
            content="synthetic newer note $index")) }
        store.correctContext(restrictionId,context("30"));admit(store)
        val captured=store.captureAdaptationResult(null,at)
        val base=ScopedEvidencePreparation.packet(captured.request,captured.evidence,
            ScopedTrends.report(captured.evidence.read.scope,captured.evidence.read.history,captured.evidence.read.statuses))
        assertTrue((base.context.evidence.optionalContexts+base.context.amendments.optionalContexts).none { it.context.contextId==confirmed.contextId })
        val prepared=PreparedAdaptationResult.prepare(captured)
        assertTrue((prepared.evidence.packet!!.context.evidence.optionalContexts+prepared.evidence.packet.context.amendments.optionalContexts)
            .any { it.context.contextId==confirmed.contextId && it.context.revision==confirmed.revision })
        assertTrue(prepared.evidence.packet.contextLinks.any { it.contextId==confirmed.contextId && it.contextRevision==confirmed.revision && it.intakeId==source.intakeId })
        assertTrue(prepared.evidence.packet.sourceBindings.any { it.intakeId==source.intakeId && it.linkedRevision==1 })
        assertTrue(prepared.evidence.manifest.dependencies.any { it.kind=="intake" && it.identity==source.intakeId && it.revision==1 })
        val published=store.publishAdaptationResult(prepared,at)
        assertEquals(store.storedScopedEvidence(published.snapshotId).bindingSha256,
            store.captureScopedEvidenceInspection(published.snapshotId,at).currentDigest())
        val beforeCorrection=PreparedAdaptationResult.prepare(store.captureAdaptationResult(null,at))
        store.correctIntake(source.intakeId,ContextIntakeCorrection(1,
            ContextIntakeRequest("corrected synthetic symptom source","user_report","synthetic","2026-10-01")),at)
        assertFails { store.captureScopedEvidenceInspection(published.snapshotId,at).currentDigest() }
        assertFailsWith<PlanConflict> { store.publishAdaptationResult(beforeCorrection,at) }
    }
}
