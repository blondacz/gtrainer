package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SignalAdaptationOwnerTest {
    private val day1=Instant.parse("2026-10-15T12:00:00Z")
    private val day2=Instant.parse("2026-10-16T12:00:00Z")
    private fun config(cooldown: Long=0)=PersonalSignalConfiguration("UTC",
        PersonalSignalSelector("hrv","ms","synthetic","synthetic","measurement","daily"),
        PersonalSignalBaseline(3,1,0.0,3),"low","absolute_limit",20.0,30.0,1,1,cooldown)
    private fun record(id:String,date:String,value:Double)=WellnessRecord("synthetic",id,date,setOf("synthetic"),
        mapOf("hrv" to Measurement(value,"ms","synthetic")))
    private fun trusted()=SignalEvidenceAdapter { samples -> samples.map { sample -> sample.copy(
        identity=sample.identity.copy(method="measurement",basis="daily",verification="verified")) } }

    private fun fixture(block:(Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("signal-adaptation-owner-");val path=dir.resolve("history.db")
        try { HistoryStore(path,Clock.fixed(day1,ZoneOffset.UTC),trusted()).use { block(path,it) } }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }

    private fun setup(store:HistoryStore,cooldown:Long=0):String {
        val plan=store.createPlan(AuthoredPlanRequest("Synthetic plan",PlanAuthor("user","synthetic"),listOf(
            PlannedSession("33333333-3333-4333-8333-333333333333",PlanAuthor("user","synthetic"),"run","easy",
                PlanSchedule("date_only",date="2026-10-16",timeZone="UTC"))),PlanPolicy("UTC",7,0)),day1)
        store.selectPlan(PlanSelectionRequest(0,plan.reference))
        store.updateReviewSchedule(0,ReviewScheduleConfiguration(enabled=true,timeZone="UTC",queuePolicy=ReviewQueuePolicy(0,0,60)),day1,null,null)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("reset","2026-10-15",40.0),
            record("baseline","2026-10-14",40.0))),day1)
        val id=UUID.randomUUID().toString()
        store.saveSignalRule(PersonalSignalRuleRequest(UUID.randomUUID().toString(),id,0,true,config(cooldown)),day1)
        return id
    }

    private fun prepare(store:HistoryStore,at:Instant)=PreparedSignalAdaptation.prepare(store.captureSignalAdaptation(at))

    @Test fun `verified signal admits once and keeps admission across reopen`()=fixture { path,store ->
        val id=setup(store)
        assertTrue(store.commitSignalAdaptation(prepare(store,day1),Clock.fixed(day1,ZoneOffset.UTC)).admittedReasonIds.isEmpty())
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("trigger","2026-10-16",10.0))),day2)
        val admitted=store.commitSignalAdaptation(prepare(store,day2),Clock.fixed(day2,ZoneOffset.UTC))
        assertEquals(1,admitted.admittedReasonIds.size)
        assertEquals("admission_candidate",admitted.signalStatuses.single())
        val state=store.captureSignalAdaptation(day2).replay.single().stored
        assertEquals(1L,state.highWater?.episodeSequence)
        assertTrue(state.checkpoint!!.state.projected.emitted)
        assertEquals(1,store.reviewScheduleState().queue.pending.single().reasons.size)
        HistoryStore(path,Clock.fixed(day2,ZoneOffset.UTC),trusted()).use { reopened ->
            assertEquals(1L,reopened.captureSignalAdaptation(day2).replay.single().stored.highWater?.episodeSequence)
            assertTrue(reopened.commitSignalAdaptation(prepare(reopened,day2),Clock.fixed(day2,ZoneOffset.UTC)).admittedReasonIds.isEmpty())
        }
        assertEquals(id,store.captureSignalAdaptation(day2).replay.single().signal.rule.ruleId)
    }

    @Test fun `unchanged evidence does not admit twice and default adapter remains unverified`()=fixture { path,store ->
        setup(store)
        store.commitSignalAdaptation(prepare(store,day1),Clock.fixed(day1,ZoneOffset.UTC))
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("trigger","2026-10-16",10.0))),day2)
        assertEquals(1,store.commitSignalAdaptation(prepare(store,day2),Clock.fixed(day2,ZoneOffset.UTC)).admittedReasonIds.size)
        assertTrue(store.commitSignalAdaptation(prepare(store,day2),Clock.fixed(day2,ZoneOffset.UTC)).admittedReasonIds.isEmpty())
        val dir=Files.createTempDirectory("signal-adaptation-unverified-")
        try { HistoryStore(dir.resolve("history.db"),Clock.fixed(day1,ZoneOffset.UTC),SignalEvidenceAdapter { it }).use { ordinary ->
            setup(ordinary)
            val p=prepare(ordinary,day1)
            assertFalse(p.signals.single().report.days.last().status=="trigger")
            assertTrue(ordinary.commitSignalAdaptation(p,Clock.fixed(day1,ZoneOffset.UTC)).admittedReasonIds.isEmpty())
            ordinary.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("trigger","2026-10-16",10.0))),day2)
            assertTrue(ordinary.commitSignalAdaptation(prepare(ordinary,day2),Clock.fixed(day2,ZoneOffset.UTC)).admittedReasonIds.isEmpty())
            assertNull(ordinary.captureSignalAdaptation(day2).replay.single().stored.highWater)
        } } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }

    private fun commit(store:HistoryStore,at:Instant)=store.commitSignalAdaptation(prepare(store,at),Clock.fixed(at,ZoneOffset.UTC))
    private fun trigger(store:HistoryStore,cooldown:Long=0):String {
        val id=setup(store,cooldown)
        commit(store,day1)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("trigger","2026-10-16",10.0))),day2)
        return id
    }
    private fun replay(store:HistoryStore,at:Instant=day2)=store.captureSignalAdaptation(at).replay.single().stored
    private fun sql(path:Path,text:String)=DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { it.execute(text) } }

    @Test fun `admitted signal work is deliverable reusable and invalidated by baseline correction`()=fixture { _,store ->
        trigger(store)
        commit(store,day2)
        val work=store.reviewScheduleState().queue.pending.single().scopedIntent!!.adaptation!!.workVersionSha256
        val water=replay(store).highWater
        store.updateReviewQueue { state -> state.copy(queue=ReviewQueueRules.claim(state.queue,day2)) to Unit }
        kotlinx.coroutines.runBlocking {
            PersonalScopedQueueAdapter(store,Clock.fixed(day2,ZoneOffset.UTC)).prepare(store.reviewScheduleState().queue.running!!)
        }
        val outcome=store.reviewScheduleState().queue.outcomes.single()
        assertEquals("factual_prepared",outcome.status)
        val stored=store.storedScopedEvidence(outcome.snapshotId!!)
        assertEquals(work,stored.snapshot.packet!!.adaptation!!.workKey)
        assertEquals("trigger",stored.snapshot.packet.adaptation!!.signals.single().current!!.status)
        assertTrue(stored.snapshot.packet.adaptation.reasons.single { it.kind=="personal_signal" }.provenance.single().startsWith("signal_rule/"))
        assertEquals(stored.bindingSha256,store.captureScopedEvidenceInspection(stored.snapshot.snapshotId,day2).currentDigest())
        assertTrue(commit(store,day2).admittedReasonIds.isEmpty())
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
        val prepared=PreparedAdaptationResult.prepare(store.captureAdaptationResult(null,day2.plusSeconds(60)))
        assertEquals(stored.snapshot.snapshotId,store.publishAdaptationResult(prepared,day2.plusSeconds(60)).snapshotId)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("baseline","2026-10-14",39.0))),day2.plusSeconds(120))
        kotlin.test.assertFails { store.captureScopedEvidenceInspection(stored.snapshot.snapshotId,day2.plusSeconds(120)).currentDigest() }
        assertEquals(water,replay(store,day2.plusSeconds(120)).highWater)
    }

    @Test fun `queue capacity and receipt failure leave no partial admission or acknowledgment`()=fixture { path,store ->
        trigger(store)
        val before=replay(store)
        val beforeCapture=store.captureSignalAdaptation(day2).root
        store.updateReviewQueue { state ->
            val intent=ScopedQueueIntent(ScopedQueueIntent.PROFILE,"period_reflection",state.version,
                ReviewScopeResolver.resolve(ScopedReviewRequest(scope="day"),day2,"UTC"),0,0)
            val jobs=(1..256).map { n -> ReviewQueueJob(UUID.randomUUID().toString(),"synthetic-$n",null,emptyList(),emptyList(),
                day2.toString(),day2.toString(),day2.toString(),day2.plusSeconds(60).toString(),scopedIntent=intent) }
            state.copy(queue=ReviewQueueState(pending=jobs)) to Unit
        }
        assertFailsWith<ReviewQueueCapacity> { commit(store,day2) }
        assertEquals(before,replay(store))
        assertEquals(beforeCapture.reasonHeads,store.captureSignalAdaptation(day2).root.reasonHeads)
        assertEquals(beforeCapture.admissions,store.captureSignalAdaptation(day2).root.admissions)
        assertEquals(256,store.reviewScheduleState().queue.pending.size)
        store.updateReviewQueue { it.copy(queue=ReviewQueueState()) to Unit }
        sql(path,"CREATE TRIGGER synthetic_signal_receipt_failure BEFORE INSERT ON adaptation_admissions BEGIN SELECT RAISE(ABORT,'synthetic refusal'); END")
        assertFailsWith<java.sql.SQLException> { commit(store,day2) }
        assertEquals(before,replay(store))
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
        assertEquals(beforeCapture.reasonHeads,store.captureSignalAdaptation(day2).root.reasonHeads)
        sql(path,"DROP TRIGGER synthetic_signal_receipt_failure")
        assertEquals(1,commit(store,day2).admittedReasonIds.size)
    }

    @Test fun `unrelated import validates emitted continuation and keeps unchanged pending job`()=fixture { _,store ->
        trigger(store);commit(store,day2)
        val before=replay(store)
        val queue=store.reviewScheduleState().queue
        val later=day2.plusSeconds(60)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(WellnessRecord("other","weight","2026-10-16",setOf("other"),
            mapOf("weight" to Measurement(75.0,"kg","other"))))),later)
        assertTrue(commit(store,later).admittedReasonIds.isEmpty())
        assertEquals(before.highWater,replay(store,later).highWater)
        assertTrue(replay(store,later).checkpoint!!.state.projected.emitted)
        assertEquals(queue,store.reviewScheduleState().queue)
    }

    @Test fun `corrected away reset retracts pending proof without forgetting durable admission`()=fixture { _,store ->
        trigger(store);commit(store,day2)
        val water=replay(store).highWater
        val later=day2.plusSeconds(60)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("reset","2026-10-15",10.0))),later)
        val result=commit(store,later)
        assertEquals(listOf("unknown_prior_state"),result.signalStatuses)
        assertTrue(result.admittedReasonIds.isEmpty())
        assertNull(replay(store,later).checkpoint)
        assertEquals(water,replay(store,later).highWater)
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
        assertEquals(1,store.captureSignalAdaptation(later).root.admissions.size)
        assertEquals("unevaluable",store.captureSignalAdaptation(later).root.reasonHeads.single { it.kind=="personal_signal" }.condition)
    }

    @Test fun `relevant correction never repeatedly cancels running adaptation job`()=fixture { _,store ->
        trigger(store);commit(store,day2)
        store.updateReviewQueue { state -> state.copy(queue=ReviewQueueRules.claim(state.queue,day2)) to Unit }
        val running=store.reviewScheduleState().queue.running!!
        val later=day2.plusSeconds(60)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("trigger","2026-10-16",12.0))),later)
        assertTrue(commit(store,later).admittedReasonIds.isEmpty())
        assertEquals(running,store.reviewScheduleState().queue.running)
        assertTrue(commit(store,later).admittedReasonIds.isEmpty())
        assertEquals(running,store.reviewScheduleState().queue.running)
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
    }

    @Test fun `fresh post frontier reset retriggers only after preserved cooldown and current persistence`()=fixture { _,store ->
        val id=trigger(store,5*86400);commit(store,day2)
        val original=replay(store).highWater!!
        val reset=day2.plusSeconds(86400)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("new-reset","2026-10-17",40.0))),reset)
        assertTrue(commit(store,reset).admittedReasonIds.isEmpty())
        val again=reset.plusSeconds(86400)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("new-trigger","2026-10-18",10.0))),again)
        assertEquals(listOf("cooldown_wait"),commit(store,again).signalStatuses)
        store.saveSignalRule(PersonalSignalRuleRequest(UUID.randomUUID().toString(),id,1,true,config(0)),again)
        assertEquals(listOf("cooldown_wait"),commit(store,again).signalStatuses)
        assertEquals(original,replay(store,again).highWater)
        val expiry=Instant.parse(original.preservedCooldownUntilUtc)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("expiry","2026-10-21",25.0))),expiry)
        assertTrue(commit(store,expiry).admittedReasonIds.isEmpty())
        val current=expiry.plusSeconds(60)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("expiry","2026-10-21",10.0))),current)
        assertEquals(1,commit(store,current).admittedReasonIds.size)
        assertEquals(2L,replay(store,current).highWater?.episodeSequence)
        assertEquals(2,store.captureSignalAdaptation(current).root.admissions.size)
    }

    @Test fun `policy disable reenable and removal reimport cannot bypass admitted frontier`()=fixture { path,store ->
        val id=trigger(store,86400);commit(store,day2)
        val water=replay(store).highWater
        store.saveSignalRule(PersonalSignalRuleRequest(UUID.randomUUID().toString(),id,1,false,null),day2)
        assertTrue(commit(store,day2).admittedReasonIds.isEmpty())
        store.saveSignalRule(PersonalSignalRuleRequest(UUID.randomUUID().toString(),id,2,true,config()),day2)
        assertTrue(commit(store,day2).admittedReasonIds.isEmpty())
        assertEquals(water,replay(store).highWater)
        store.removeImports()
        assertNull(replay(store).checkpoint)
        assertEquals(water,replay(store).highWater)
        val later=day2.plusSeconds(60)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("baseline","2026-10-14",40.0),
            record("reset","2026-10-15",40.0),record("trigger","2026-10-16",10.0))),later)
        assertTrue(commit(store,later).admittedReasonIds.isEmpty())
        assertEquals(water,replay(store,later).highWater)
        HistoryStore(path,Clock.fixed(later,ZoneOffset.UTC),trusted()).use { reopened ->
            assertEquals(water,replay(reopened,later).highWater)
            assertTrue(commit(reopened,later).admittedReasonIds.isEmpty())
        }
    }

    @Test fun `owner local foreign ABA and midnight races reject without partial receipt`()=fixture { path,store ->
        trigger(store)
        var captured=prepare(store,day2)
        assertFailsWith<PlanConflict> { store.commitSignalAdaptation(captured,Clock.fixed(day2.plusSeconds(43200),ZoneOffset.UTC)) }
        store.createContext(AthleteContextRequest("note","user_report","synthetic","2026-10-15",content="local mutation"))
        assertFailsWith<PlanConflict> { store.commitSignalAdaptation(captured,Clock.fixed(day2,ZoneOffset.UTC)) }
        HistoryStore(path,Clock.fixed(day2,ZoneOffset.UTC),trusted()).use { foreign ->
            captured=prepare(store,day2)
            foreign.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("foreign","2026-10-13",40.0))),day2)
            assertFailsWith<PlanConflict> { store.commitSignalAdaptation(captured,Clock.fixed(day2,ZoneOffset.UTC)) }
            captured=prepare(foreign,day2)
            assertFailsWith<PlanConflict> { store.commitSignalAdaptation(captured,Clock.fixed(day2,ZoneOffset.UTC)) }
        }
        captured=prepare(store,day2)
        val selected=store.plans().selection
        store.selectPlan(PlanSelectionRequest(selected.generation,null))
        store.selectPlan(PlanSelectionRequest(selected.generation+1,selected.selected))
        assertFailsWith<PlanConflict> { store.commitSignalAdaptation(captured,Clock.fixed(day2,ZoneOffset.UTC)) }
        assertTrue(store.captureSignalAdaptation(day2).root.admissions.isEmpty())
        assertNull(replay(store).highWater)
    }

    @Test fun `same day A B A changes keep proof invalid after original values return`()=fixture { _,store ->
        trigger(store);commit(store,day2)
        val water=replay(store).highWater
        val later=day2.plusSeconds(60)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("reset","2026-10-15",10.0))),later)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(record("reset","2026-10-15",40.0))),later.plusSeconds(1))
        assertTrue(commit(store,later.plusSeconds(1)).admittedReasonIds.isEmpty())
        assertNull(replay(store,later.plusSeconds(1)).checkpoint)
        assertEquals(water,replay(store,later.plusSeconds(1)).highWater)
    }

    @Test fun `date move and incompatible insertion lose continuity without a duplicate`() {
        for(move in listOf(false,true)) fixture { _,store ->
            trigger(store);commit(store,day2)
            val water=replay(store).highWater
            val later=day2.plusSeconds(60)
            val changed=if(move) record("reset","2026-10-16",40.0) else record("conflicting-reset","2026-10-15",40.0)
            store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(changed)),later)
            assertTrue(commit(store,later).admittedReasonIds.isEmpty())
            assertNull(replay(store,later).checkpoint)
            assertEquals(water,replay(store,later).highWater)
        }
    }

    @Test fun `continuous episode stays emitted beyond cooldown and a multi day processing gap`()=fixture { _,store ->
        trigger(store,86400);commit(store,day2)
        val water=replay(store).highWater
        val later=day2.plusSeconds(4*86400)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,(17..20).map { record("day-$it","2026-10-$it",10.0) }),later)
        assertTrue(commit(store,later).admittedReasonIds.isEmpty())
        assertEquals(water,replay(store,later).highWater)
        assertTrue(replay(store,later).checkpoint!!.state.projected.emitted)
    }

    @Test fun `causal watch accepts 732 dates then refuses growth without forgetting admission`()=fixture { _,store ->
        trigger(store);commit(store,day2)
        val water=replay(store).highWater
        var previous=1
        for(offset in listOf(300,600,728,729)) {
            val at=day1.plusSeconds(offset*86400L)
            val rows=((previous+1)..offset).map { day ->
                record("long-$day",day1.plusSeconds(day*86400L).atZone(ZoneOffset.UTC).toLocalDate().toString(),10.0)
            }
            store.completeWellness(ReadResult(ReadStatus.SUCCESS,rows),at)
            val result=commit(store,at)
            assertTrue(result.admittedReasonIds.isEmpty())
            assertEquals(water,replay(store,at).highWater)
            if(offset<=728) {
                assertTrue(replay(store,at).checkpoint!!.state.projected.emitted)
                assertEquals("2026-10-12",replay(store,at).checkpoint!!.binding.oldest)
            } else {
                assertEquals(listOf("causal_watch_unavailable"),result.signalStatuses)
                assertNull(replay(store,at).checkpoint)
            }
            previous=offset
        }
    }

    @Test fun `context deletion scrubs replay and reason hashes but preserves opaque admission facts`()=fixture { _,store ->
        val target=store.createContext(AthleteContextRequest("note","user_report","synthetic","2026-10-15",content="synthetic deletion target"))
        trigger(store);commit(store,day2)
        val before=store.captureSignalAdaptation(day2)
        val preview=store.previewContextDeletion(target.contextId,day2)
        store.redactAndDeleteContext(target.contextId,ContextDeletionRequest(preview.expectedRevision,preview.digest,emptyList(),emptyList(),emptyList()),day2)
        val after=store.captureSignalAdaptation(day2)
        assertNull(after.replay.single().stored.checkpoint)
        assertEquals(before.replay.single().stored.highWater,after.replay.single().stored.highWater)
        assertEquals(before.root.admissions,after.root.admissions)
        val beforeSignal=before.root.reasonHeads.single { it.kind=="personal_signal" }
        val afterSignal=after.root.reasonHeads.single { it.kind=="personal_signal" }
        assertEquals("unevaluable",afterSignal.condition)
        assertFalse(beforeSignal.materialSha256==afterSignal.materialSha256)
        assertTrue(commit(store,day2).admittedReasonIds.isEmpty())
        assertNull(replay(store).checkpoint)
    }
}
