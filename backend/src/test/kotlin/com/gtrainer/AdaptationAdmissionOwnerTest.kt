package com.gtrainer

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AdaptationAdmissionOwnerTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")

    private fun fixture(block:(HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("adaptation-admission-owner-")
        try { HistoryStore(dir.resolve("history.db"),Clock.fixed(at,ZoneOffset.UTC)).use(block) }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }

    private fun context(value:String="60")=AthleteContextRequest("restriction","user_report","synthetic author",
        "2026-10-01",content="synthetic prose",restrictionKind="maximum_duration",restrictionValue=value,restrictionUnit="minutes")

    private fun setup(store:HistoryStore):String {
        val item=store.createContext(context())
        val plan=store.createPlan(AuthoredPlanRequest("Synthetic plan",PlanAuthor("user","synthetic"),listOf(
            PlannedSession("33333333-3333-4333-8333-333333333333",PlanAuthor("user","synthetic"),"run","easy",
                PlanSchedule("date_only",date="2026-10-18",timeZone="UTC"))),PlanPolicy("UTC",7,0)),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference))
        store.updateReviewSchedule(0,ReviewScheduleConfiguration(enabled=true,timeZone="UTC",queuePolicy=ReviewQueuePolicy(0,0,60)),at,null,null)
        return item.contextId
    }

    private fun capture(store:HistoryStore,time:Instant=at)=PreparedSignalAdaptation.prepare(store.captureSignalAdaptation(time))
    private fun commit(store:HistoryStore,time:Instant=at)=store.commitSignalAdaptation(capture(store,time),Clock.fixed(time,ZoneOffset.UTC))
    private fun workload(store:HistoryStore,aggregation:String="per_record",days:Int=7):String {
        val id=UUID.randomUUID().toString()
        store.saveWorkloadRule(ExtraWorkloadRuleRequest(UUID.randomUUID().toString(),id,0,true,
            ExtraWorkloadConfiguration(aggregation,"duration","seconds","moving","ride","intervals.icu",null,"UTC",days,"greater_than",100.0)),at)
        return id
    }
    private fun activity(id:String,value:Double,date:String="2026-10-15")=syntheticActivity().copy(sourceRecordId=id,sport="ride",
        startLocal="${date}T10:00:00",startInstant="${date}T10:00:00Z",timeContext="upstream_instant",timeZone="UTC",movingTime=Measurement(value,"seconds"))
    private fun imported(store:HistoryStore,vararg records:ActivityRecord,time:Instant=at)=store.completeActivities(ReadResult(ReadStatus.SUCCESS,records.toList()),time)
    private fun extra(store:HistoryStore,time:Instant=at)=store.captureSignalAdaptation(time).root.reasonHeads.filter { it.kind=="extra_workload" }.single()

    @Test fun `baseline enrollment creates inactive deterministic heads without queue admission`()=fixture { store ->
        setup(store)
        val result=commit(store)
        val root=store.captureSignalAdaptation(at).root
        assertTrue(result.admittedReasonIds.isEmpty())
        assertTrue(root.reasonHeads.isNotEmpty())
        assertTrue(root.reasonHeads.none { it.condition=="active" })
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
        assertTrue(root.admissions.isEmpty())
    }

    @Test fun `selected constraint A B A admits event receipts and coalesces one family job`()=fixture { store ->
        val contextId=setup(store)
        commit(store)
        store.correctContext(contextId,context("30"))
        store.correctContext(contextId,context("60"))
        val prepared=capture(store)
        val active=prepared.heads.filter { it.kind=="changed_constraint" && it.condition=="active" }
        assertEquals(2,active.size)
        assertNotEquals(active[0].reasonId,active[1].reasonId)
        assertTrue(prepared.work!=null)
        val admitted=store.commitSignalAdaptation(prepared,Clock.fixed(at,ZoneOffset.UTC))
        assertEquals(2,admitted.admittedReasonIds.size)
        assertEquals(1,store.reviewScheduleState().queue.pending.count { it.scopedIntent?.adaptation!=null })
        assertEquals(2,store.captureSignalAdaptation(at).root.admissions.count { it.reasonId in admitted.admittedReasonIds })
        assertTrue(commit(store).admittedReasonIds.isEmpty())
        assertEquals(1,store.reviewScheduleState().queue.pending.count { it.scopedIntent?.adaptation!=null })
    }

    @Test fun `abnormal correction revises dependencies without readmission and positive clear retriggers`()=fixture { store ->
        setup(store);workload(store);imported(store,activity("extra",120.0))
        assertEquals(1,commit(store).admittedReasonIds.size)
        store.updateReviewQueue { state -> state.copy(queue=ReviewQueueRules.claim(state.queue,at)) to Unit }
        kotlinx.coroutines.runBlocking { PersonalScopedQueueAdapter(store,Clock.fixed(at,ZoneOffset.UTC)).prepare(store.reviewScheduleState().queue.running!!) }
        val first=extra(store)
        imported(store,activity("extra",140.0))
        assertTrue(commit(store).admittedReasonIds.isEmpty())
        assertTrue(extra(store).revision>first.revision)
        assertEquals(first.episode,extra(store).episode)
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
        imported(store,activity("extra",50.0))
        assertTrue(commit(store).admittedReasonIds.isEmpty())
        assertFalse(extra(store).open)
        imported(store,activity("extra",120.0))
        assertEquals(1,commit(store).admittedReasonIds.size)
        assertEquals(first.episode+1,extra(store).episode)
        assertEquals(2,store.captureSignalAdaptation(at).root.admissions.size)
    }

    @Test fun `disabled rule retains open episode and contributor support without creating a clear`()=fixture { store ->
        setup(store);val id=workload(store);imported(store,activity("extra",120.0));commit(store)
        val first=extra(store)
        val configuration=store.workloadRules().single().configuration
        store.saveWorkloadRule(ExtraWorkloadRuleRequest(UUID.randomUUID().toString(),id,1,false,null),at)
        assertTrue(commit(store).admittedReasonIds.isEmpty())
        assertEquals("unevaluable",extra(store).condition);assertTrue(extra(store).open)
        assertTrue(store.captureSignalAdaptation(at).root.reasonSupports.single { it.reasonId==first.reasonId }.activityIds.isNotEmpty())
        store.saveWorkloadRule(ExtraWorkloadRuleRequest(UUID.randomUUID().toString(),id,2,true,configuration),at)
        assertTrue(commit(store).admittedReasonIds.isEmpty())
        assertEquals(first.episode,extra(store).episode)
        assertEquals("active",extra(store).condition)
    }

    @Test fun `partial cumulative contributor aging cannot manufacture a clearing or new episode`()=fixture { store ->
        setup(store);workload(store,"cumulative",2)
        imported(store,activity("old",80.0,"2026-10-14"),activity("retained",50.0))
        assertEquals(1,commit(store).admittedReasonIds.size)
        val first=extra(store)
        val tomorrow=at.plusSeconds(86400)
        assertTrue(commit(store,tomorrow).admittedReasonIds.isEmpty())
        assertEquals("provisional",extra(store,tomorrow).condition)
        assertTrue(extra(store,tomorrow).open)
        imported(store,activity("retained",150.0),time=tomorrow)
        assertTrue(commit(store,tomorrow).admittedReasonIds.isEmpty())
        assertEquals(first.episode,extra(store,tomorrow).episode)
        assertEquals(2,store.captureSignalAdaptation(tomorrow).root.reasonSupports.single { it.reasonId==first.reasonId }.contributors.size)
    }

    @Test fun `different reason kinds coalesce and unrelated note preserves canonical work`()=fixture { store ->
        val id=setup(store);workload(store);imported(store,activity("extra",120.0))
        store.correctContext(id,context("30"))
        val prepared=capture(store)
        val expected=prepared.heads.filter { it.condition=="active" }.map { it.reasonId }.toSet()
        assertEquals(setOf("changed_constraint","extra_workload"),prepared.heads.filter { it.condition=="active" }.map { it.kind }.toSet())
        assertEquals(expected,store.commitSignalAdaptation(prepared,Clock.fixed(at,ZoneOffset.UTC)).admittedReasonIds.toSet())
        val job=store.reviewScheduleState().queue.pending.single()
        assertEquals(expected,job.reasons.toSet())
        val key=capture(store).work!!.key()
        store.createContext(AthleteContextRequest("note","user_report","synthetic","2026-10-15",content="unrelated note"))
        assertEquals(key,capture(store).work!!.key())
        assertTrue(commit(store).admittedReasonIds.isEmpty())
        assertEquals(job,store.reviewScheduleState().queue.pending.single())
    }

    @Test fun `deselected constraint changes coalesce on return and inactive family heads stay unchanged`()=fixture { store ->
        val id=setup(store)
        store.correctContext(id,context("30"));commit(store)
        val original=store.plans().selection
        val heads=store.captureSignalAdaptation(at).root.reasonHeads
        val other=store.createPlan(AuthoredPlanRequest("Other plan",PlanAuthor("user","synthetic"),emptyList(),PlanPolicy("UTC",7,0)),at)
        store.selectPlan(PlanSelectionRequest(original.generation,other.reference))
        commit(store)
        assertEquals(heads,store.captureSignalAdaptation(at).root.reasonHeads.filter { h -> heads.any { it.reasonId==h.reasonId } })
        store.correctContext(id,context("60"));store.correctContext(id,context("30"))
        store.selectPlan(PlanSelectionRequest(store.plans().selection.generation,original.selected))
        assertEquals(2,commit(store).admittedReasonIds.size)
        assertEquals(3,store.captureSignalAdaptation(at).root.admissions.size)
        assertTrue(commit(store).admittedReasonIds.isEmpty())
    }
}
