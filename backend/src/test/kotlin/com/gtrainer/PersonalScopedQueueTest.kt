package com.gtrainer

import kotlinx.coroutines.*
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class PersonalScopedQueueTest {
    private val at=Instant.parse("2020-06-01T08:01:00Z")
    private fun fixture(block: (java.nio.file.Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("personal-scoped-fence-");val path=dir.resolve("history.sqlite3")
        try { HistoryStore(path).use { store ->
            store.updateReviewSchedule(0,ReviewScheduleConfiguration(enabled=true,timeZone="UTC",queuePolicy=ReviewQueuePolicy(0,0,60)),at,null,null)
            block(path,store)
        } } finally { Files.list(dir).use { files->files.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
    private fun count(path: java.nio.file.Path,table: String)=java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
        db.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $table").use { r -> r.next();r.getInt(1) } }
    }
    private fun claim(store: HistoryStore,time: Instant=at): ReviewQueueJob {
        val state=store.reviewScheduleState();val revisions=store.scopedRoutingRevisions()
        val scope=ReviewScopeResolver.resolve(ScopedReviewRequest(scope="day",anchorDate="2020-06-01"),time,"UTC")
        val intent=ScopedQueueIntent(ScopedDescriptiveContractV1.PROFILE,"morning",state.version,scope,revisions.source,revisions.planReason)
        return store.updateReviewQueue { current ->
            val pending=ReviewQueueRules.enqueueScoped(current.queue,listOf(ReviewQueueRules.ScopedQueueSubmission(intent,
                AnalysisClaims.hash(time.toString()),listOf("purpose_slot"),manual=true)),current.configuration.queuePolicy,time)
            val next=ReviewQueueRules.claim(pending,time)
            current.copy(queue=next) to assertNotNull(next.running)
        }
    }
    private fun prepare(capture: CapturedScopedQueueJob)=prepareScopedEvidence(capture.request,capture.evidence,
        ScopedTrends.report(capture.evidence.read.scope,capture.evidence.read.history,capture.evidence.read.statuses))

    private fun planRequest()=AuthoredPlanRequest("Synthetic plan",PlanAuthor("user","synthetic"),listOf(
        PlannedSession("33333333-3333-4333-8333-333333333333",PlanAuthor("user","synthetic"),"run","easy",
             PlanSchedule("date_only",date="2020-06-01",timeZone="UTC"))))

    @Test fun `unintegrated adaptation family job cannot publish an ordinary descriptive result`() = fixture { path,store ->
        val authored=store.createPlan(planRequest(),at)
        val selection=store.selectPlan(PlanSelectionRequest(0,authored.reference))
        val state=store.reviewScheduleState();val revisions=store.scopedRoutingRevisions()
        val scope=ReviewScopeResolver.resolve(ScopedReviewRequest(scope="day",anchorDate="2020-06-01"),at,"UTC")
        val payload=AdaptationQueuePayload(AdaptationFamily(authored.reference,1,"UTC",authored.reference.planId),"a".repeat(64),selection.generation,listOf("b".repeat(64)))
        val intent=ScopedQueueIntent(ScopedQueueIntent.PROFILE,"adaptation",state.version,scope,revisions.source,revisions.planReason,adaptation=payload)
        val job=store.updateReviewQueue { current ->
            val pending=ReviewQueueRules.enqueueScoped(current.queue,listOf(ReviewQueueRules.ScopedQueueSubmission(intent,"synthetic-adaptation",payload.reasonIds,true)),current.configuration.queuePolicy,at)
            val claimed=ReviewQueueRules.claim(pending,at)
            current.copy(queue=claimed) to assertNotNull(claimed.running)
        }
        assertFailsWith<IllegalArgumentException> { store.captureScopedQueueJob(job,at) }
        assertEquals(job,store.reviewScheduleState().queue.running)
        assertEquals(0,count(path,"scoped_evidence_snapshots"))
        assertEquals(0,count(path,"scoped_execution_bindings"))
        assertEquals(0,count(path,"adaptation_admissions"))
    }

    @Test fun `morning packet retains today plan context and dated latest sleep with late arrival fenced`() = fixture { path,store ->
        val plan=store.createPlan(planRequest(),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference))
        val context=store.createContext(AthleteContextRequest("note","user_report","synthetic","2020-06-01",content="Synthetic context"))
        val job=claim(store)
        val missing=store.captureScopedQueueJob(job,at)
        val missingPacket=ScopedEvidencePreparation.packet(missing.request,missing.evidence,
            ScopedTrends.report(missing.evidence.read.scope,missing.evidence.read.history,missing.evidence.read.statuses))
        assertTrue(assertNotNull(missingPacket.latestAvailableWellness).missing.any { it.contains("sleep") })
        assertEquals(plan.reference,missingPacket.plan.plan)
        assertEquals(plan.definition.sessions,missingPacket.plan.sessions)
        assertTrue(missingPacket.context.evidence.optionalContexts.any { it.context.contextId==context.contextId })
        val first=prepare(missing)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(WellnessRecord("synthetic","late-sleep","2020-05-31",emptySet(),
            mapOf("sleepSecs" to Measurement(28000.0,"seconds"))))),at.plusSeconds(60))
        assertFailsWith<ScopedEvidenceConflict> { store.publishScopedQueueJob(missing,first,at.plusSeconds(60)) }
        assertEquals(job,store.reviewScheduleState().queue.running)
        assertEquals(0,count(path,"scoped_evidence_snapshots"))
        val fresh=store.captureScopedQueueJob(job,at.plusSeconds(61))
        val snapshot=store.publishScopedQueueJob(fresh,prepare(fresh),at.plusSeconds(62))
        assertEquals("2020-06-01T08:02:01Z",snapshot.manifest.scope.asOf)
        val sleep=assertNotNull(snapshot.packet?.latestAvailableWellness).days.single { it.field=="sleepSecs" }
        assertEquals("2020-05-31",sleep.sourceDate);assertEquals(1,sleep.ageDays)
        assertEquals("factual_prepared",store.reviewScheduleState().queue.outcomes.single().status)
        assertEquals(plan.reference,store.plans().selection.selected)
    }

    @Test fun `plan and commitment mutations fence capture without superseding running queue work`() = fixture { path,store ->
        val plan=store.createPlan(planRequest(),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference))
        val source=store.createIntake(ContextIntakeRequest("Synthetic commitment","user_report","synthetic","2020-06-01"),at)
        val job=claim(store)
        fun mutationFences(mutate: ()->Unit) {
            val capture=store.captureScopedQueueJob(job,at)
            val prepared=prepare(capture)
            val before=store.scopedRoutingRevisions()
            mutate()
            assertTrue(store.scopedRoutingRevisions().planReason>before.planReason)
            assertEquals(job,store.reviewScheduleState().queue.running)
            assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
            assertFailsWith<ScopedEvidenceConflict> { store.publishScopedQueueJob(capture,prepared,at) }
            assertEquals(0,count(path,"scoped_evidence_snapshots"))
        }
        mutationFences { store.correctPlan(plan.reference.planId,PlanCorrection(1,1,plan.definition.copy(title="Changed synthetic plan")),at) }
        mutationFences { store.confirmCommitment(CommitmentConfirmation(source.intakeId,1,
            CommitmentDefinition("Synthetic commitment","commitment","UTC","2020-06-01T12:00","2020-06-01T13:00")),at) }
        val fresh=store.captureScopedQueueJob(job,at)
        store.publishScopedQueueJob(fresh,prepare(fresh),at)
        assertNull(store.reviewScheduleState().queue.running)
        assertEquals(1,count(path,"scoped_evidence_snapshots"))
    }

    @Test fun `captured source changes and lost queue ownership cannot insert or resurrect a snapshot`() = fixture { path,store ->
        val job=claim(store)
        val captured=store.captureScopedQueueJob(job,at)
        val prepared=prepare(captured)
        store.createContext(AthleteContextRequest("symptom","user_report","athlete","2020-06-01",content="Synthetic unresolved symptom"))
        assertFailsWith<ScopedEvidenceConflict> { store.publishScopedQueueJob(captured,prepared,at) }
        assertEquals(0,count(path,"scoped_evidence_snapshots"));assertEquals(job,store.reviewScheduleState().queue.running)
        val refreshed=store.captureScopedQueueJob(job,at)
        val freshPrepared=prepare(refreshed)
        store.updateReviewQueue { state -> state.copy(queue=ReviewQueueRules.invalidate(state.queue,"fixture_cancel",at)) to Unit }
        assertFailsWith<ScopedEvidenceConflict> { store.publishScopedQueueJob(refreshed,freshPrepared,at) }
        assertEquals(0,count(path,"scoped_evidence_snapshots"));assertNull(store.reviewScheduleState().queue.running)
        assertEquals("fixture_cancel",store.reviewScheduleState().queue.outcomes.single().reason)
    }

    @Test fun `unrelated bookkeeping conflicts but does not advance durable source reasons and a new capture succeeds`() = fixture { path,store ->
        val initial=store.scopedRoutingRevisions()
        val record=syntheticActivity()
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record)),at)
        val changed=store.scopedRoutingRevisions();assertTrue(changed.source>initial.source)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record)),at.plusSeconds(1))
        assertEquals(changed,store.scopedRoutingRevisions())
        val job=claim(store)
        val captured=store.captureScopedQueueJob(job,at)
        val prepared=prepare(captured)
        store.updateReviewQueue { it to Unit }
        assertEquals(changed,store.scopedRoutingRevisions())
        assertFailsWith<ScopedEvidenceConflict> { store.publishScopedQueueJob(captured,prepared,at) }
        assertEquals(0,count(path,"scoped_evidence_snapshots"))
        val refreshed=store.captureScopedQueueJob(job,at.plusSeconds(2))
        val snapshot=store.publishScopedQueueJob(refreshed,prepare(refreshed),at.plusSeconds(2))
        assertEquals("2020-06-01T08:01:02Z",snapshot.manifest.scope.asOf)
        assertEquals(job.scopedIntent?.scope?.asOf,store.reviewScheduleState().queue.outcomes.single().scopedIntent?.scope?.asOf)
        assertEquals(changed,store.scopedRoutingRevisions());assertEquals(1,count(path,"scoped_evidence_snapshots"))
    }

    @Test fun `owned snapshot retention is bounded and preserves manual and execution referenced evidence`() = fixture { path,store ->
        val request=ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="day",anchorDate="2020-06-01"),purpose="period_reflection")
        val captured=store.captureScopedEvidence(request,at)
        val manual=store.saveScopedEvidence(prepareScopedEvidence(request,captured,
            ScopedTrends.report(captured.read.scope,captured.read.history,captured.read.statuses)),at)
        var pinned: String?=null
        repeat(27) { n ->
            val time=at.plusSeconds(n.toLong())
            val job=claim(store,time);val capture=store.captureScopedQueueJob(job,time)
            val snapshot=store.publishScopedQueueJob(capture,prepare(capture),time)
            if(n==0) {
                pinned=snapshot.snapshotId
                ScopedExecutionService(store).prepare(ScopedDescriptiveContractV1.PROFILE,ScopedExecutionSource(snapshotId=pinned),
                    ConnectedReviewProviderSelection("synthetic","synthetic-model","local"))
            }
        }
        assertEquals(24,store.reviewScheduleState().queue.outcomes.size)
        assertEquals(26,count(path,"scoped_evidence_snapshots")) // 24 retained outcomes + manual + pinned execution source.
        assertEquals(25,count(path,"scoped_queue_snapshot_ownership"))
        assertNotNull(store.storedScopedEvidence(manual.snapshotId));assertNotNull(store.storedScopedEvidence(assertNotNull(pinned)))
        assertEquals(0,count(path,"connected_review_snapshots"));assertNull(store.reviewScheduleState().queue.lastSuccess)
    }

    @Test fun `recapture takes a fresh owner clock instant across midnight and completion remains separate`() = fixture { _,store ->
        val clock=ReviewTestClock(Instant.parse("2020-06-01T23:59:00Z"))
        val job=claim(store,clock.instant())
        val first=store.captureScopedQueueJob(job,clock);val prepared=prepare(first)
        clock.now=Instant.parse("2020-06-02T00:01:00Z")
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(WellnessRecord("synthetic","late-sleep","2020-06-02",emptySet(),
            mapOf("sleepSecs" to Measurement(28000.0,"seconds"))))),clock.instant())
        assertFailsWith<ScopedEvidenceConflict> { store.publishScopedQueueJob(first,prepared,clock.instant()) }
        val second=store.captureScopedQueueJob(job,clock)
        assertEquals("2020-06-02T00:01:00Z",second.evidence.read.scope.asOf)
        val snapshot=store.publishScopedQueueJob(second,prepare(second),clock.instant().plusSeconds(2))
        assertEquals("2020-06-01",snapshot.manifest.scope.anchorDate)
        assertEquals("2020-06-02",snapshot.packet?.latestAvailableWellness?.evaluationDate)
        assertEquals("2020-06-02T00:01:02Z",store.reviewScheduleState().queue.outcomes.single().finishedUtc)
    }

    @Test fun `source status changes invalidate factual currentness without importing duplicate records or requesting more work`() = fixture { path,store ->
        store.begin("activities",at)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(syntheticActivity())),at)
        val imported=count(path,"review_import_changes")
        val job=claim(store);val capture=store.captureScopedQueueJob(job,at)
        store.publishScopedQueueJob(capture,prepare(capture),at)
        val before=store.scopedRoutingRevisions()
        store.begin("activities",at.plusSeconds(1))
        val running=store.scopedRoutingRevisions();assertTrue(running.source>before.source)
        store.interrupted("activities");assertTrue(store.scopedRoutingRevisions().source>running.source)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(syntheticActivity())),at.plusSeconds(2))
        assertEquals(imported,count(path,"review_import_changes"))
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
        assertEquals(1,store.reviewScheduleState().queue.outcomes.size)
    }

    @Test fun `outcome eviction from cancellation bookkeeping purges owned packets without waiting for another preparation`() = fixture { path,store ->
        val job=claim(store);val capture=store.captureScopedQueueJob(job,at)
        val snapshot=store.publishScopedQueueJob(capture,prepare(capture),at)
        assertEquals(1,count(path,"scoped_evidence_snapshots"))
        repeat(24) { n ->
            claim(store,at.plusSeconds(n+1L))
            store.updateReviewQueue { state -> state.copy(queue=ReviewQueueRules.invalidate(state.queue,"synthetic_cancel",at)) to Unit }
        }
        assertEquals(0,count(path,"scoped_evidence_snapshots"));assertEquals(0,count(path,"scoped_queue_snapshot_ownership"))
        assertFailsWith<ScopedEvidenceNotFound> { store.storedScopedEvidence(snapshot.snapshotId) }
    }

    @Test fun `scheduled personal scope is prepared without a provider and restart requires rearm`() = runBlocking {
        val directory = Files.createTempDirectory("personal-scoped-queue-")
        val path = directory.resolve("store.sqlite3")
        val clock = ReviewTestClock(Instant.parse("2020-06-01T07:00:00Z"))
        var store = HistoryStore(path)
        var reviews = ReviewInterpretationService(emptyList(), ReviewExecutionPolicy(), null)
        var scheduler = ReviewScheduler(store, reviews, clock, durableQueue = true)
        var queue = ReviewQueueService(store, reviews, clock)
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fun configuration() = ReviewScheduleConfiguration(enabled = true, timeZone = "UTC",
            presets = defaultReviewPresets(),
            queuePolicy = ReviewQueuePolicy(0, 0, 60),
            purposeSlots = listOf(ReviewPurposeSlot("11111111-1111-4111-8111-111111111111", "morning", true, "08:00")))
        suspend fun configure() = scheduler.configure(ReviewScheduleUpdate(store.reviewScheduleState().version, configuration()))
        suspend fun eventually(predicate: () -> Boolean) = withTimeout(5000) {
            while (!predicate()) { yield(); delay(1) }
        }
        try {
            configure()
            clock.now = Instant.parse("2020-06-01T08:01:00Z")
            val due = scheduler.tick().purposeDue
            assertEquals(1, due.size)
            assertEquals("morning", due.single().purpose)
            assertEquals(1, store.reviewScheduleState().queue.pending.size)
            queue.pump(worker)
            eventually { store.reviewScheduleState().queue.outcomes.isNotEmpty() }
            val outcome = queue.status().outcomes.single()
            assertEquals("factual_prepared", outcome.status)
            assertEquals("2020-06-01T08:01:00Z", outcome.scopedIntent!!.scope.asOf)
            assertNotNull(outcome.snapshotId)
            assertEquals(0, reviews.status().models.size)
            repeat(3) { scheduler.tick(); queue.pump(worker) }
            assertEquals(1, queue.status().outcomes.size)

            queue.close(); reviews.close(); store.close()
            store = HistoryStore(path)
            reviews = ReviewInterpretationService(emptyList(), ReviewExecutionPolicy(), null)
            scheduler = ReviewScheduler(store, reviews, clock, durableQueue = true)
            queue = ReviewQueueService(store, reviews, clock)
            assertEquals("awaiting_explicit_rearm", scheduler.status().purposeSlots.single().state)
            assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
            clock.now = Instant.parse("2020-06-01T08:02:00Z")
            configure()
            assertTrue(scheduler.tick().purposeDue.isEmpty())
            assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
            assertEquals(outcome.snapshotId, store.storedScopedEvidence(requireNotNull(outcome.snapshotId)).snapshot.snapshotId)
        } finally {
            queue.close(); reviews.close(); worker.cancel()
            store.close()
            Files.list(directory).use { files -> files.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(directory)
        }
    }
}
