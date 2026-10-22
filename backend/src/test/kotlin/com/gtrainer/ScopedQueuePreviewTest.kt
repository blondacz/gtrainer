package com.gtrainer

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ScopedQueuePreviewTest {
    private val at=Instant.parse("2020-06-01T08:01:00Z")

    private fun fixture(block:(java.nio.file.Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("scoped-queue-preview-");val path=dir.resolve("history.sqlite3")
        try { HistoryStore(path).use { store ->
            store.updateReviewSchedule(0,ReviewScheduleConfiguration(enabled=true,timeZone="UTC",queuePolicy=ReviewQueuePolicy(0,0,60)),at,null,null)
            block(path,store)
        } } finally { Files.list(dir).use { files->files.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }

    private fun count(path:java.nio.file.Path,table:String)=java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
        db.createStatement().use { s->s.executeQuery("SELECT count(*) FROM $table").use { r->r.next();r.getInt(1) } }
    }

    private fun claimed(store:HistoryStore,kind:String):ReviewQueueJob {
        val revisions=store.scopedRoutingRevisions()
        val scope=ReviewScopeResolver.resolve(ScopedReviewRequest(scope="day",anchorDate="2020-06-01"),at,"UTC")
        val selection=ConnectedReviewProviderSelection("synthetic","synthetic-model",kind)
        val intent=ScopedQueueIntent(ScopedDescriptiveContractV1.PROFILE,"morning",store.reviewScheduleState().version,scope,
            revisions.source,revisions.planReason,selection)
        return store.updateReviewQueue { current ->
            val queued=ReviewQueueRules.enqueueScoped(current.queue,listOf(ReviewQueueRules.ScopedQueueSubmission(intent,
                AnalysisClaims.hash(at.toString()),listOf("purpose_slot"),manual=false)),current.configuration.queuePolicy,at)
            val next=ReviewQueueRules.claim(queued,at)
            current.copy(queue=next) to assertNotNull(next.running)
        }
    }

    @Test fun `explicit hosted and local selection creates exact inert preview after factual commit`()=fixture { path,store->
        for(kind in listOf("hosted","local")) {
            val job=claimed(store,kind)
            runBlocking { PersonalScopedQueueAdapter(store,Clock.fixed(at,ZoneOffset.UTC)).prepare(job) }
            val outcome=store.reviewScheduleState().queue.outcomes.single { it.id==job.id }
            assertEquals("factual_prepared",outcome.status)
            val snapshot=store.storedScopedEvidence(assertNotNull(outcome.snapshotId)).snapshot
            assertEquals(snapshot.manifest.scope.asOf,outcome.evidenceCutoffUtc)
            assertEquals("2020-06-01T08:01:00Z",outcome.evidenceCutoffUtc)
            assertEquals(if(kind=="hosted") "awaiting_consent" else "blocked_admission",outcome.preview?.state)
            assertNotNull(outcome.preview?.executionId)
            val execution=ScopedExecutionService(store).inspect(assertNotNull(outcome.preview?.executionId))
            assertEquals(snapshot.snapshotId,execution.binding.source.snapshotId)
            assertEquals(kind,execution.binding.providerSelection.providerKind)
            assertEquals(restoredScopedInvocation(execution.binding).exactPrompt,execution.binding.exactPrompt)
            assertEquals(restoredScopedInvocation(execution.binding).consentDigest,execution.binding.consentSha256)
            assertEquals(0,count(path,"connected_review_snapshots"))
            assertNull(execution.validatedOutputJson)
        }
    }

    @Test fun `preview ownership insert rollback preserves factual outcome without execution binding`()=fixture { path,store->
        val job=claimed(store,"hosted")
        val capture=store.captureScopedQueueJob(job,at)
        val snapshot=store.publishScopedQueueJob(capture,prepareScopedEvidence(capture.request,capture.evidence,
            ScopedTrends.report(capture.evidence.read.scope,capture.evidence.read.history,capture.evidence.read.statuses)),at)
        val outcome=store.reviewScheduleState().queue.outcomes.single { it.id==job.id }
        val prepared=ScopedExecutionService(store).assemble(ScopedDescriptiveContractV1.PROFILE,
            ScopedExecutionSource(snapshotId=snapshot.snapshotId),requireNotNull(job.scopedIntent?.providerSelection))
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER reject_preview_ownership BEFORE INSERT ON scoped_queue_preview_ownership BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        }
        assertFailsWith<java.sql.SQLException> { store.attachScopedQueuePreview(job,snapshot.snapshotId,prepared) }
        val after=store.reviewScheduleState().queue.outcomes.single { it.id==job.id }
        assertEquals(outcome,after)
        assertEquals("factual_prepared",after.status)
        assertEquals(snapshot.snapshotId,after.snapshotId)
        assertEquals("not_prepared",after.preview?.state)
        assertEquals(0,count(path,"scoped_execution_bindings"))
        assertNull(after.preview?.executionId)
    }

    @Test fun `automatic preview ownership and snapshots are bounded together`()=fixture { path,store->
        val preparedIds=mutableListOf<String>()
        val executionIds=mutableListOf<String>()
        val context=store.createContext(AthleteContextRequest("note","user_report","synthetic","2020-06-01",content="Synthetic retained original context"))
        repeat(27) { n ->
            val time=at.plusSeconds(n.toLong())
            val revisions=store.scopedRoutingRevisions()
            val scope=ReviewScopeResolver.resolve(ScopedReviewRequest(scope="day",anchorDate="2020-06-01"),time,"UTC")
            val selection=ConnectedReviewProviderSelection("synthetic","synthetic-model","local")
            val intent=ScopedQueueIntent(ScopedDescriptiveContractV1.PROFILE,"morning",store.reviewScheduleState().version,scope,
                revisions.source,revisions.planReason,selection)
            val job=store.updateReviewQueue { current ->
                val queued=ReviewQueueRules.enqueueScoped(current.queue,listOf(ReviewQueueRules.ScopedQueueSubmission(intent,
                    AnalysisClaims.hash("automatic-$n"),listOf("purpose_slot"),manual=false)),current.configuration.queuePolicy,time)
                val next=ReviewQueueRules.claim(queued,time)
                current.copy(queue=next) to assertNotNull(next.running)
            }
            val capture=store.captureScopedQueueJob(job,time)
            val snapshot=store.publishScopedQueueJob(capture,prepareScopedEvidence(capture.request,capture.evidence,
                ScopedTrends.report(capture.evidence.read.scope,capture.evidence.read.history,capture.evidence.read.statuses)),time)
            val execution=ScopedExecutionService(store).assemble(ScopedDescriptiveContractV1.PROFILE,
                ScopedExecutionSource(snapshotId=snapshot.snapshotId),selection)
            store.attachScopedQueuePreview(job,snapshot.snapshotId,execution)
            if(n==0) { preparedIds+=snapshot.snapshotId;executionIds+=execution.binding.executionId }
        }
        val outcomes=store.reviewScheduleState().queue.outcomes
        assertEquals(24,outcomes.size)
        assertEquals(24,outcomes.count { it.preview?.executionId!=null })
        assertEquals(24,count(path,"scoped_queue_preview_ownership"))
        assertEquals(24,count(path,"scoped_execution_bindings"))
        assertEquals(24,count(path,"scoped_evidence_snapshots"))
        assertTrue(outcomes.all { it.scopedIntent?.let { intent -> intent.providerSelection!=null }==true })
        assertFailsWith<ScopedEvidenceNotFound> { store.storedScopedEvidence(preparedIds.single()) }
        assertFailsWith<ConnectedReviewNotFound> { store.captureScopedExecution(executionIds.single()) }
        assertEquals(context,store.contextRevisions(context.contextId).single())
    }
}
