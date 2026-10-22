package com.gtrainer

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class ScopedQueuePreviewRaceTest {
    private val at=Instant.parse("2020-06-01T08:01:00Z")
    private val selected=ConnectedReviewProviderSelection("synthetic","synthetic-model","hosted")
    private fun fixture(block: (Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("synthetic-preview-races-");val path=dir.resolve("history.sqlite3")
        try { HistoryStore(path).use { store ->
            store.updateReviewSchedule(0,ReviewScheduleConfiguration(enabled=true,timeZone="UTC",queuePolicy=ReviewQueuePolicy(0,0,60)),at,null,null)
            block(path,store)
        } } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
    private fun claim(store: HistoryStore,time: Instant=at,selection: ConnectedReviewProviderSelection?=selected,manual: Boolean=false): ReviewQueueJob {
        val state=store.reviewScheduleState();val revisions=store.scopedRoutingRevisions()
        val scope=ReviewScopeResolver.resolve(ScopedReviewRequest(scope="day",anchorDate="2020-06-01"),time,"UTC")
        val intent=ScopedQueueIntent(ScopedDescriptiveContractV1.PROFILE,"morning",state.version,scope,revisions.source,revisions.planReason,selection)
        return store.updateReviewQueue { current ->
            val pending=ReviewQueueRules.enqueueScoped(current.queue,listOf(ReviewQueueRules.ScopedQueueSubmission(intent,
                AnalysisClaims.hash(time.toString()),listOf("purpose_slot"),manual=manual)),current.configuration.queuePolicy,time)
            val next=ReviewQueueRules.claim(pending,time)
            current.copy(queue=next) to assertNotNull(next.running)
        }
    }
    private fun factual(store: HistoryStore,job: ReviewQueueJob,time: Instant=at): ScopedEvidenceSnapshot {
        val capture=store.captureScopedQueueJob(job,time)
        val prepared=prepareScopedEvidence(capture.request,capture.evidence,
            ScopedTrends.report(capture.evidence.read.scope,capture.evidence.read.history,capture.evidence.read.statuses))
        return store.publishScopedQueueJob(capture,prepared,time)
    }
    private fun draft(store: HistoryStore,snapshot: ScopedEvidenceSnapshot)=ScopedExecutionService(store,ReviewTestClock(at)).assemble(
        ScopedDescriptiveContractV1.PROFILE,ScopedExecutionSource(snapshotId=snapshot.snapshotId),selected)

    @Test fun `factual publication rollback leaves no snapshot ownership or finished outcome`()=fixture { path,store ->
        val job=claim(store);val capture=store.captureScopedQueueJob(job,at)
        val prepared=prepareScopedEvidence(capture.request,capture.evidence,
            ScopedTrends.report(capture.evidence.read.scope,capture.evidence.read.history,capture.evidence.read.statuses))
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER synthetic_finish_failure BEFORE UPDATE ON review_schedule BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
            assertFailsWith<java.sql.SQLException> { store.publishScopedQueueJob(capture,prepared,at) }
            for(table in listOf("scoped_evidence_snapshots","scoped_queue_snapshot_ownership","scoped_execution_bindings"))
                db.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $table").use { r -> r.next();assertEquals(0,r.getInt(1)) } }
        }
        assertEquals(job,store.reviewScheduleState().queue.running)
        assertTrue(store.reviewScheduleState().queue.outcomes.isEmpty())
    }

    @Test fun `configuration source generation and retained outcome changes fence preview attachment`() {
        for(change in listOf("pause","configuration","source","generation","eviction")) fixture { _,store ->
            val job=claim(store);val snapshot=factual(store,job);val prepared=draft(store,snapshot)
            when(change) {
                "pause","configuration" -> {
                    val state=store.reviewScheduleState()
                    store.updateReviewSchedule(state.version,state.configuration.copy(paused=change=="pause",timeZone=if(change=="configuration") "Europe/Prague" else "UTC"),at,null,null)
                }
                "source" -> store.createContext(AthleteContextRequest("note","user_report","synthetic","2020-06-01",content="Changed synthetic context"))
                "generation" -> ScopedExecutionService(store,ReviewTestClock(at)).prepare(ScopedDescriptiveContractV1.PROFILE,
                    ScopedExecutionSource(snapshotId=snapshot.snapshotId),selected)
                "eviction" -> repeat(24) { n -> val time=at.plusSeconds(n+1L);factual(store,claim(store,time,null),time) }
            }
            assertFailsWith<ScopedEvidenceConflict>(change) { store.attachScopedQueuePreview(job,snapshot.snapshotId,prepared) }
            assertTrue(store.reviewScheduleState().queue.outcomes.none { it.preview?.executionId==prepared.binding.executionId })
            assertFailsWith<ConnectedReviewNotFound> { store.captureScopedExecution(prepared.binding.executionId) }
        }
    }

    @Test fun `factual cutoff is actual read not routed or completion time`()=fixture { _,store ->
        val job=claim(store,Instant.parse("2020-06-01T23:59:00Z"))
        val readAt=Instant.parse("2020-06-02T00:01:00Z")
        val capture=store.captureScopedQueueJob(job,readAt)
        val prepared=prepareScopedEvidence(capture.request,capture.evidence,
            ScopedTrends.report(capture.evidence.read.scope,capture.evidence.read.history,capture.evidence.read.statuses))
        store.publishScopedQueueJob(capture,prepared,readAt.plusSeconds(30))
        val outcome=store.reviewScheduleState().queue.outcomes.single()
        assertEquals("2020-06-01T23:59:00Z",outcome.scopedIntent?.scope?.asOf)
        assertEquals("2020-06-02T00:01:00Z",outcome.evidenceCutoffUtc)
        assertEquals("2020-06-02T00:01:30Z",outcome.finishedUtc)
    }

    @Test fun `restart between commits leaves not prepared and repeated status does not repair or write`()=fixture { path,store -> runBlocking {
        val job=claim(store);val snapshot=factual(store,job)
        store.close()
        HistoryStore(path).use { reopened ->
            ReviewInterpretationService(emptyList(),ReviewExecutionPolicy(),null).use { reviews ->
                val queue=ReviewQueueService(reopened,reviews,ReviewTestClock(at))
                try {
                    val before=reopened.storedScopedEvidence(snapshot.snapshotId).totalChanges
                    repeat(3) {
                        val outcome=queue.status().outcomes.single()
                        assertEquals("not_prepared",outcome.preview?.state);assertNull(outcome.preview?.executionId)
                        assertEquals(snapshot.snapshotId,outcome.snapshotId)
                    }
                    assertEquals(before,reopened.storedScopedEvidence(snapshot.snapshotId).totalChanges)
                    assertTrue(reopened.reviewScheduleState().queue.pending.isEmpty())
                } finally { queue.close() }
            }
        }
    } }

    @Test fun `discarded and source changed previews cannot retain awaiting consent status on GET`()=fixture { _,store -> runBlocking {
        val first=claim(store)
        PersonalScopedQueueAdapter(store,ReviewTestClock(at)).prepare(first)
        val outcome=store.reviewScheduleState().queue.outcomes.single()
        val execution=assertNotNull(outcome.preview?.executionId)
        ReviewInterpretationService(emptyList(),ReviewExecutionPolicy(),null).use { reviews ->
            val queue=ReviewQueueService(store,reviews,ReviewTestClock(at))
            try {
                assertEquals("awaiting_consent",queue.status().outcomes.single().preview?.state)
                store.createContext(AthleteContextRequest("note","user_report","synthetic","2020-06-01",content="Changed synthetic context"))
                val before=store.captureScopedExecution(execution).source.totalChanges
                assertEquals("stale",queue.status().outcomes.single().preview?.state)
                assertEquals(before,store.captureScopedExecution(execution).source.totalChanges)
                store.discardScopedExecution(execution)
                val after=store.storedScopedEvidence(assertNotNull(outcome.snapshotId)).totalChanges
                assertEquals("unavailable",queue.status().outcomes.single().preview?.state)
                assertEquals(after,store.storedScopedEvidence(outcome.snapshotId).totalChanges)
            } finally { queue.close() }
        }
    } }

    @Test fun `eviction preserves manual previews attempted reviews and unresolved runtime ownership`()=fixture { _,store -> runBlocking {
        val manualRequest=ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="day",anchorDate="2020-06-01"),purpose="period_reflection")
        val captured=store.captureScopedEvidence(manualRequest,at)
        val manual=store.saveScopedEvidence(prepareScopedEvidence(manualRequest,captured,
            ScopedTrends.report(captured.read.scope,captured.read.history,captured.read.statuses)),at)
        val manualExecution=ScopedExecutionService(store,ReviewTestClock(at)).prepare(ScopedDescriptiveContractV1.PROFILE,
            ScopedExecutionSource(snapshotId=manual.snapshotId),selected).binding.executionId
        val manualQueued=claim(store,at.minusSeconds(1),manual=true)
        PersonalScopedQueueAdapter(store,ReviewTestClock(at)).prepare(manualQueued)
        val manualQueuedExecution=assertNotNull(store.reviewScheduleState().queue.outcomes.last().preview?.executionId)
        val protected=mutableListOf<String>()
        repeat(2) { n ->
            val time=at.plusSeconds(n.toLong())
            PersonalScopedQueueAdapter(store,ReviewTestClock(time)).prepare(claim(store,time))
            val id=assertNotNull(store.reviewScheduleState().queue.outcomes.last().preview?.executionId)
            protected+=id
            val capture=store.captureScopedExecution(id)
            val resource=PersonalResourceIdentity("domain-$n","runtime-$n",selected)
            val observation=PersonalResourceObservation(resource,time,time.plusSeconds(600),true)
            val clock=ReviewTestClock(time)
            val lease=assertNotNull(store.reservePersonalReview(capture,capture.current(),store.personalOwnerEpoch(),observation,resource,clock,600_000))
            assertTrue(store.beginPersonalStart(lease,observation,clock))
            if(n==1) {
                assertNotNull(ScopedExecutionRepository(store,lease,clock).claimConnectedReview(id))
                assertTrue(store.authorizePersonalAttempt(lease,1,observation,clock))
                store.revokePersonalReview(lease)
                assertTrue(store.confirmPersonalStop(store.personalOwnerEpoch(),lease,PersonalStopEvidence(lease,time,true,true),time))
            }
        }
        repeat(24) { n -> val time=at.plusSeconds(n+2L);factual(store,claim(store,time,null),time) }
        for(id in protected+manualExecution+manualQueuedExecution) assertNotNull(store.captureScopedExecution(id))
        assertNotNull(store.storedScopedEvidence(manual.snapshotId))
        assertEquals(1,store.unresolvedPersonalReviews().size)
    } }
}
