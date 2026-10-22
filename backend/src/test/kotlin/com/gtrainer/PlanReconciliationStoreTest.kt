package com.gtrainer

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.util.UUID

class PlanReconciliationStoreTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private val query=PlanReconciliationQuery("2026-10-15","2026-10-15","UTC")
    private val first="22222222-2222-4222-8222-222222222222"
    private val second="33333333-3333-4333-8333-333333333333"
    private fun definition(two: Boolean=false,policy: Boolean=true)=AuthoredPlanRequest("Synthetic plan",PlanAuthor("user","Synthetic author"),
        (if(two) listOf(first,second) else listOf(first)).map { id -> PlannedSession(id,PlanAuthor("coach","Synthetic coach"),"Run","Synthetic session",
            PlanSchedule("timed",startsLocal="2026-10-15T10:00",endsLocal="2026-10-15T11:00",timeZone="UTC")) },
        if(policy) PlanPolicy("UTC",30,3600) else null)
    private fun record(unknown: Boolean=false)=syntheticActivity().copy(sourceRecordId="reconcile-synthetic",sport="Run",startLocal="2026-10-15T10:15:00",
        startInstant=if(unknown) null else "2026-10-15T10:15:00Z",timeZone=null,utcOffset=null,timeContext=if(unknown) "local_time_only" else "upstream_instant")
    private fun fixture(two: Boolean=false,policy: Boolean=true,block: (java.nio.file.Path,HistoryStore,ReviewTestClock,PlanService)->Unit) {
        val dir=Files.createTempDirectory("reconciliation-transaction-");val path=dir.resolve("history.db")
        try { HistoryStore(path).use { store ->
            val clock=ReviewTestClock(at);val service=PlanService(store,clock)
            val plan=service.create(definition(two,policy));service.select(PlanSelectionRequest(0,plan.reference))
            block(path,store,clock,service)
        } } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
    private fun request(view: PlanReconciliationView,action: String="match",session: String=first,activity: String?=view.activities.firstOrNull()?.activity?.activityId): PlanReconciliationConfirmationRequest {
        return PlanReconciliationConfirmationRequest(UUID.randomUUID().toString(),assertNotNull(view.selection.selected),session,action,
            if(action=="match") activity else null,view.selection.generation,view.sessions.single { it.sessionId==session }.confirmation?.confirmationId,view.digest,view.asOf,view.query)
    }

    @Test fun `repeated whole-store capture is read only and deterministic`() {
        val dir=Files.createTempDirectory("reconciliation-store-"); val store=HistoryStore(dir.resolve("history.db"))
        try {
            val clock=Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"),ZoneOffset.UTC)
            val query=PlanReconciliationQuery("2026-10-15","2026-10-15","UTC")
            val before=store.capturePlanReconciliation(clock.instant())
            val first=PlanService(store,clock).reconciliation(query)
            val middle=store.capturePlanReconciliation(clock.instant())
            val second=PlanService(store,clock).reconciliation(query)
            val after=store.capturePlanReconciliation(clock.instant())
            assertEquals(before.totalChanges,middle.totalChanges)
            assertEquals(middle.totalChanges,after.totalChanges)
            assertEquals(first.digest,second.digest)
            assertEquals(emptyList(),first.sessions)
        } finally { store.close();Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }

    @Test fun `stable manual match replays once survives reopen and corrections require new confirmation`() = fixture { path,store,clock,service ->
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record())),at)
        val selected=service.list().selection;val plan=service.get(assertNotNull(selected.selected).planId)
        val view=service.reconciliation(query);val input=request(view)
        val before=store.scopedRoutingRevisions()
        val receipt=service.confirmReconciliation(input)
        assertFalse(receipt.replayed);assertEquals("authenticated_user",receipt.confirmation.recordedBy)
        val revisions=store.scopedRoutingRevisions();assertEquals(before.planReason+1,revisions.planReason)
        assertEquals("user_confirmation",service.reconciliation(query).sessions.single().basis)
        assertTrue(service.confirmReconciliation(input).replayed);assertEquals(revisions,store.scopedRoutingRevisions())
        assertFailsWith<PlanConflict> { service.confirmReconciliation(input.copy(action="skip",activityId=null)) }
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record().copy(calories=Measurement(124.0,"kcal")))),at.plusSeconds(1))
        val corrected=service.reconciliation(query);assertEquals("needs_confirmation",corrected.sessions.single().state)
        assertTrue(service.confirmReconciliation(input).replayed)
        val renewed=service.confirmReconciliation(request(corrected));assertFalse(renewed.replayed)
        assertEquals(selected,service.list().selection);assertEquals(plan,service.get(plan.reference.planId))
        store.close()
        HistoryStore(path).use { reopened ->
            val restored=PlanService(reopened,clock).reconciliation(query)
            assertEquals(renewed.confirmation,restored.sessions.single().confirmation)
            assertEquals("matched",restored.sessions.single().state)
            assertTrue(PlanService(reopened,clock).confirmReconciliation(input).replayed)
            assertTrue(reopened.reviewScheduleState().queue.pending.isEmpty())
        }
    }

    @Test fun `manual confirmation resolves ambiguity but cannot steal another explicit owner`() = fixture(two=true) { _,store,_,service ->
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record())),at)
        val view=service.reconciliation(query);assertTrue(view.sessions.all { it.state=="ambiguous" })
        service.confirmReconciliation(request(view))
        val reserved=service.reconciliation(query)
        assertEquals("matched",reserved.sessions.first { it.sessionId==first }.state)
        val before=store.scopedRoutingRevisions()
        assertFailsWith<PlanConflict> { service.confirmReconciliation(request(reserved,session=second)) }
        assertEquals(before,store.scopedRoutingRevisions())
        service.confirmReconciliation(request(reserved,"clear"))
        val cleared=service.reconciliation(query)
        service.confirmReconciliation(request(cleared,session=second))
        assertEquals(second,service.reconciliation(query).sessions.single { it.basis=="user_confirmation" }.sessionId)
    }

    @Test fun `skip is explicit grace bounded and contradictory late imports retract its finding`() = fixture { _,store,clock,service ->
        clock.now=at.minusSeconds(1)
        val early=service.reconciliation(query)
        assertEquals("awaiting_evidence",early.sessions.single().state)
        assertFailsWith<PlanConflict> { service.confirmReconciliation(request(early,"skip")) }
        clock.now=at
        val ready=service.reconciliation(query);assertEquals("provisional_unmatched",ready.sessions.single().state)
        service.confirmReconciliation(request(ready,"skip"))
        assertEquals("confirmed_skipped",service.reconciliation(query).sessions.single().state)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record())),at.plusSeconds(1))
        val late=service.reconciliation(query)
        assertEquals("needs_confirmation",late.sessions.single().state)
        assertEquals("recorded_evidence_contradicts_confirmed_skip",late.sessions.single().basis)
        assertFailsWith<PlanConflict> { service.confirmReconciliation(request(late,"skip")) }
    }

    @Test fun `skip without policy and replacing an explicit match are refused while unknown timing can be linked explicitly`() {
        fixture(policy=false) { _,_,_,service -> assertFailsWith<PlanConflict> { service.confirmReconciliation(request(service.reconciliation(query),"skip")) } }
        fixture { _,store,_,service ->
            store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record(unknown=true))),at)
            val view=service.reconciliation(query);assertEquals("needs_confirmation",view.activities.single().state)
            service.confirmReconciliation(request(view))
            val linked=service.reconciliation(query);assertEquals("matched",linked.sessions.single().state)
            assertEquals("user_confirmation_timing_unverified",linked.sessions.single().basis)
            assertNull(linked.activities.single().activity.startInstant)
            assertFailsWith<PlanConflict> { service.confirmReconciliation(request(linked,"skip")) }
        }
    }

    @Test fun `stale read plan changes new candidate and late eligibility cannot bypass owner fences`() = fixture { _,store,clock,service ->
        val view=service.reconciliation(query);val oldSkip=request(view,"skip")
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record())),at)
        assertFailsWith<PlanConflict> { service.confirmReconciliation(oldSkip) }
        val matched=service.reconciliation(query);val input=request(matched)
        val capture=store.capturePlanReconciliation(at)
        store.updateReviewQueue { it to Unit }
        assertFailsWith<PlanConflict> { store.confirmPlanReconciliation(input,capture,capture.activities.single(),clock) }
        val current=service.reconciliation(query)
        service.select(PlanSelectionRequest(current.selection.generation,current.selection.selected))
        assertFailsWith<PlanConflict> { service.confirmReconciliation(request(current)) }
        val selected=service.reconciliation(query)
        val plan=service.get(assertNotNull(selected.selection.selected).planId)
        service.correct(plan.reference.planId,PlanCorrection(1,selected.selection.generation,plan.definition.copy(title="Corrected plan")))
        assertFailsWith<PlanConflict> { service.confirmReconciliation(request(selected)) }
        assertTrue(service.reconciliation(query).sessions.all { it.confirmation==null })
    }

    @Test fun `future known recording cannot be confirmed through an old cutoff or forged future view`() = fixture { _,store,clock,service ->
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record().copy(startInstant="2026-10-15T13:00:00Z",startLocal="2026-10-15T13:00:00"))),at)
        val view=service.reconciliation(query)
        assertFailsWith<PlanConflict> { service.confirmReconciliation(request(view)) }
        assertFailsWith<PlanConflict> { service.confirmReconciliation(request(view).copy(asOf=clock.instant().plusSeconds(1).toString())) }
    }

    @Test fun `authored omission preserves old explicit ownership until its confirmation is cleared`() = fixture(two=true) { _,store,_,service ->
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record())),at)
        service.confirmReconciliation(request(service.reconciliation(query)))
        val before=service.reconciliation(query);val selected=assertNotNull(before.selection.selected);val plan=service.get(selected.planId)
        service.correct(selected.planId,PlanCorrection(selected.revision,before.selection.generation,
            plan.definition.copy(sessions=plan.definition.sessions.map { if(it.sessionId==first) it.copy(omitted=true) else it })))
        val omitted=service.reconciliation(query)
        assertEquals("omitted",omitted.sessions.single { it.sessionId==first }.state)
        assertFailsWith<PlanConflict> { service.confirmReconciliation(request(omitted,session=second)) }
        service.confirmReconciliation(request(omitted,"clear"))
        service.confirmReconciliation(request(service.reconciliation(query),session=second))
        assertEquals(second,service.reconciliation(query).sessions.single { it.basis=="user_confirmation" }.sessionId)
    }

    @Test fun `fresh owner time rejects skip when an originally future candidate becomes observed without another write`() = fixture { _,store,clock,service ->
        clock.now=at.minusSeconds(3*3600)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record().copy(startLocal="2026-10-15T10:15:00",startInstant="2026-10-15T10:15:00Z"))),clock.instant())
        // The original 09:00 cutoff hides a 10:15 start, but the final 12:00 owner clock cannot.
        val view=service.reconciliation(query)
        val capture=store.capturePlanReconciliation(clock.instant())
        val input=request(view,"skip")
        clock.now=at
        assertFailsWith<PlanConflict> { store.confirmPlanReconciliation(input,capture,null,clock) }
    }

    @Test fun `removal after capture prevents stale reconciliation response and confirmation restoration`() = fixture { _,store,clock,service ->
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record())),at)
        service.confirmReconciliation(request(service.reconciliation(query)))
        val capture=store.capturePlanReconciliation(at)
        val pending=PlanReconciler.reconcile(query,at,capture)
        val changes=capture.totalChanges
        assertEquals(pending,store.finishPlanReconciliation(capture,pending))
        assertEquals(changes,store.capturePlanReconciliation(at).totalChanges)
        val input=request(pending)
        store.removeImports()
        assertFailsWith<PlanConflict> { store.finishPlanReconciliation(capture,pending) }
        assertFailsWith<PlanConflict> { store.confirmPlanReconciliation(input,capture,capture.activities.single(),clock) }
        val latest=service.reconciliation(query)
        assertTrue(latest.activities.isEmpty());assertNull(latest.sessions.single().confirmation)
        assertEquals("provisional_unmatched",latest.sessions.single().state)
    }
}
